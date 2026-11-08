# 学习通助手（XXTSign）

Android 原生 App，多账号登录学习通，支持批量签到和视频刷课。**纯协议实现，无需 root**：不复用学习通 App 的数据，自己在 App 内维护每个账号的登录态。

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
| 视频刷课 | 选账号、获取课程/章节、按并发与倍速完成视频任务点 |
| 课程缓存 | 课程与章节列表按账号自动保存，重新进入直接载入；顶部「刷新课程」手动拉取最新列表 |
| 任务点检查 | 展开课程自动检查每章全部任务点，蓝/绿/灰三色标注，支持本地缓存和手动刷新 |
| 章节级选择 | 可逐章勾选；一键选中只选视频未完成的章节 |

## 刷课（仅视频）

1. 在「账号」页给要刷课的账号勾选「刷课」（单选）。
2. 首次在「刷课」页点「获取课程」。课程和后续加载过的章节会按账号自动保存；之后重新进入 App/切换回该账号会直接显示本地缓存，不再自动请求课程列表。
3. 需要最新课程时可点顶部「刷新课程」；要更新单门课的章节和任务点，点该课程卡片上的「刷新」。
4. 展开课程会自动检查该课程每章的任务点（含视频、章节测验、文档）：全部任务点完成显示蓝色，只有视频任务点完成显示绿色，视频未完成显示灰色；检查结果会按账号保存到本地，重启后仍然保留。检查失败的章节不会写成「未开放」，而是标红「检查失败」且不落缓存，可点课程卡片「刷新」重试。
5. 勾课程或「全选待刷」只选中已展开检查过的课程里视频任务点未完成的章节。
6. 调整并发（1–8 章）与倍速（1.0–2.0x），点「开始刷课」。
7. 开始后会生成一张任务卡片，点进去可以看每个视频任务的进度与结果。
8. 任务在前台服务中运行，退到后台/锁屏也会继续；通知栏可查看进度并停止。
9. 如果服务端返回的播放位置已到末尾但任务仍未通过，会保持末尾位置并持续上报（服务端会持续收到播放心跳），直到 `isPassed=true`；任务详情会显示“客户端进度已完成，服务端未完成，持续播放等待服务端结束”。为避免服务端一直不确认导致任务挂死，末尾最多等待 3 分钟，超时后该视频标记失败并停止。

只实现视频任务点。章节测验/题库、文档、阅读、直播、章节学习次数均未包含；音频任务也未做单独适配。

## 构建

```bash
# 需要 JDK 17+ 与 Android SDK（compileSdk 35 / build-tools 36.0.0）
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

首次构建需要联网。`gradle.properties` 里的代理配置默认已注释；如本机有代理，自行取消注释。

## 代码结构

```
app/src/main/java/com/xxt/sign/
├── App.kt                      Application，初始化存储
├── MainActivity.kt             底部四 Tab 壳（账号 / 签到 / 刷课 / 日志）
├── MonitorService.kt           前台服务：常驻巡检 + 通知
├── study/                      刷课（仅视频）
│   ├── StudyApi.kt             课程/章节/任务点/视频进度接口
│   ├── StudyParser.kt          jsoup 解析课程、章节、视频卡片
│   ├── StudyEngine.kt          视频任务编排（并发/倍速/403 恢复/重试）
│   ├── StudyManager.kt         刷课状态中心 + 任务列表
│   ├── StudyCheckStore.kt      任务点检查结果本地缓存（按账号）
│   ├── StudyCourseStore.kt     课程/章节列表本地缓存（按账号）
│   ├── StudyService.kt         刷课前台服务 + 通知 + 唤醒锁
├── ui/
│   ├── StudyFragment.kt          刷课页
│   ├── StudyDetailActivity.kt    任务详情页
│   └── StudyCourseAdapter.kt     课程/章节/检查结果列表
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
├── sign/SignEngine.kt          多账号批量签到编排
└── ui/                         账号 / 签到 / 日志 Fragment + Adapter
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

本 App 用**轮询**近似实现，并做了两层省请求优化：

- **课程白名单**：只查你勾选的课堂，把 `K` 压到最小；
- **课程列表缓存**：10 分钟内不重复拉课程列表。

当前版本没有实现多账号共享探测，每个账号仍各自查询。

配合可调频率（1 / 2 / 3 / 5 / 10 / 20 / 30 秒），单账号 6 门课时 1 秒一轮约 6 次请求。

## 已知限制

- 刷课只处理视频任务点；章节测验、文档、阅读、直播、章节学习次数不在自动刷课范围内；
- 课程/章节缓存不会自动过期；课程内容有变化时点顶部「刷新课程」或课程卡片「刷新」；
- 任务点检查依赖服务端 `knowledge/cards` 的 `isPassed` 字段；字段缺失时会按未完成处理，可点课程卡片「刷新」重新检查；
- 服务端可能返回“播放位置已到末尾但 `isPassed=false`”的假进度，此时按原项目逻辑持续发心跳，直到服务端通过；末尾最多等待 3 分钟，超时按失败处理；
- 登录可能触发图形验证码 / 风控，此时需要换个时间重试；
- 账号密码以明文保存在 App 私有目录（`SharedPreferences`），未做 Keystore 加密；
- 未实现环信 IM 推送（真正的秒级零轮询方案）；
- 位置签到若无教师坐标且未手动设置，会退化成 `-1,-1`，大概率签不上。

## 许可

本项目以 GPL-3.0 发布。视频刷课协议参考 `Samueli924/chaoxing`，详见 `NOTICE` 与 `LICENSE`。

## 提醒

仅供个人账号的自动化使用。批量代签涉及校规与平台服务条款风险，请自行判断。
