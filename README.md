# 学习通多账号签到助手（XXTSign）

Android 原生 App，多账号登录学习通并批量签到。**纯协议实现，无需 root**：不复用学习通 App 的数据，自己在 App 内维护每个账号的登录态。

## 功能

| 功能 | 说明 |
|---|---|
| 多账号 | 账号密码登录，Cookie 会话本地持久化，登录一次长期复用 |
| 一键签到 | 自动发现每个账号当前进行中的签到活动并签到 |
| 扫码签到 | 相机扫教室大屏二维码，所有账号一起签 |
| 签到码 / 手势 | 可自动取服务端签到码，也可手动输入 |
| 位置签到 | 优先用教师指定坐标，可手动覆盖 |
| 拍照签到 | 任选一张本地图片，自动上传到超星云盘取 `objectId` |
| 自动巡检 | 前台服务常驻，有签到自动签 + 弹通知 |

## 构建

```bash
# 需要 JDK 17+ 与 Android SDK（compileSdk 35 / build-tools 36.0.0）
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次构建需要联网。若走本机代理，`gradle.properties` 里的 `systemProp.*.proxyHost` 已配好。

## 代码结构

```
app/src/main/java/com/xxt/sign/
├── App.kt                      Application，初始化存储
├── MainActivity.kt             底部三 Tab 壳（账号 / 签到 / 日志）
├── MonitorService.kt           前台服务：常驻巡检 + 通知
├── core/
│   ├── Account.kt              账号数据模型（含 Cookie 会话字段）
│   ├── AccountStore.kt         SharedPreferences 持久化 + StateFlow
│   ├── LogStore.kt             运行日志
│   └── Settings.kt             课堂白名单 / 坐标 / 图片 / 巡检频率
├── net/
│   ├── Http.kt                 OkHttp 基础封装 + 学习通 UA
│   ├── CxCrypto.kt             登录密码 AES 加密
│   ├── CxCookieJar.kt          可序列化的 CookieJar（每账号独立）
│   ├── CxSession.kt            登录 / 会话校验
│   ├── CxApi.kt                全部签到接口 + 课程缓存
│   └── QrPayload.kt            二维码内容解析
├── sign/SignEngine.kt          多账号批量编排（探针优化）
└── ui/                         三个 Fragment + 三个 Adapter
```

## 协议要点（实测结论）

### 1. 登录

优先 `GET https://passport2.chaoxing.com/api/login?name=&pwd=&schoolid=&verify=`（明文，直接返回 `uid/realname/schoolid`）。

失败则回退官方网页接口 `POST https://passport2.chaoxing.com/fanyalogin`：

- 表单字段 `fid / uname / password / refer / t / doubleFactorLogin / forbidotherlogin / independentId / validate`
- 当 `t=true` 时，**`uname` 和 `password` 都要加密**：
  - 算法 `AES/CBC/PKCS7`
  - 密钥与 IV 都是 16 字节字符串 `u2oh6Vu^HWe4_AES`
  - 输出密文的**标准 Base64**
- `refer` 在页面里本身已被编码过一次，表单提交时再编码一次，最终是双重编码
- `forbidotherlogin=0` 表示允许多终端同时在线（避免踢掉手机上的学习通）

> 登录页里还留着一个 `DES/ECB` 的加密函数，但当前登录流程已不再使用，别照抄。

### 2. 签到类型

活动详情里的 `otherId` 决定签到方式：

| `otherId` | 类型 | 关键参数 |
|---|---|---|
| 0 | 普通签到 / 拍照签到（`ifPhoto==1`） | `objectId`（拍照时） |
| 2 | 二维码签到 | `enc` |
| 3 | 手势签到 | `signCode` |
| 4 | 位置签到 | `address` + `latitude` + `longitude` + `ifTiJiao=1` |
| 5 | 签到码签到 | `signCode` |

### 3. 请求链路

```
GET  /v2/apis/active/student/activelist?fid=&courseId=&classId=&_=   找进行中的活动
GET  /newsign/signDetail?activePrimaryId=<aid>&type=1                活动详情（含 signCode/enc/code/经纬度）
GET  /newsign/preSign?courseId=&classId=&activePrimaryId=&...        预签到（必做）
GET  /pptSign/analysis + /pptSign/analysis2                          位置签到必备
GET  /pptSign/stuSignajax?<按类型拼参数>                              真正的签到
```

返回 `success` = 成功，`您已签到过了` = 已签，`success2` = 已过期。

### 4. 动态二维码（重点）

二维码签到的 `enc` **几秒就变一次**，扫描到的那个基本已经失效。所以：

1. 扫码**只用来拿 `aid`**（活动 ID），不依赖扫到的 `enc`；
2. 每个账号调 `signDetail` 拿服务端**当前**的 `enc`；
3. 当 `ifRefreshEwm == 1` 时，预签到还要带上
   `rcode=SIGNIN:aid=<aid>&source=15&Code=<code>&enc=<enc>`。

结果是：扫一次码，所有账号各自取最新的 `enc` 去签。当然，如果你在扫码那一刻刚好有活动在跑，"一键签到"也能直接识别出二维码活动并自动取 `enc`，连扫都不用扫。

### 5. 关于"有签到就弹出来"

学习通**没有**"列出全部进行中签到"的 REST 接口——活动列表是按 `courseId + classId` 查的。官方 App 能做到秒弹，靠的是**环信 IM 长连接推送**（`message.ext.attachment.att_chat_course` 里 `atype==2` 即签到）。

本 App 目前用**轮询**近似实现，并做了三层省请求优化：

- **探针共享**：多账号默认共享一次探测（课程相同时，一个账号遍历一遍即可，其余账号直接复用结果），请求量从 `N × K` 降到 `K`；
- **课程白名单**：只查你勾选的课堂，把 `K` 压到最小；
- **课程列表缓存**：10 分钟内不重复拉课程列表。

配合可调频率（1 / 2 / 3 / 5 / 10 / 20 / 30 秒），单账号 6 门课时 1 秒一轮约 6 次请求。

## 已知限制

- 登录可能触发图形验证码 / 风控，此时需要换个时间重试；
- 账号密码以明文保存在 App 私有目录（`SharedPreferences`），未做 Keystore 加密；
- 未实现环信 IM 推送（真正的秒级零轮询方案）；
- 位置签到若无教师坐标且未手动设置，会退化成 `-1,-1`，大概率签不上。

## 提醒

仅供个人账号的自动化使用。批量代签涉及校规与平台服务条款风险，请自行判断。
