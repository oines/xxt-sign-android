# 学习通助手（XXTSign）

Android 原生 App，多账号登录学习通，支持批量签到和视频刷课。**纯协议实现，无需 root**：不复用学习通 App 的数据，自己在 App 内维护每个账号的登录态。

## 它到底做成什么（验收标准）

**一门课丢给它，最后课程页的任务点应该全绿。**

具体到可判定的三条：

1. 所有视频任务点完成；
2. 所有章节测验提交并拿到分；
3. 课程 / 章节列表里没有红色未完成任务点。

做到这一点靠一条流水线，不是几个互不相干的功能：

```
体检（还差什么） → 排任务 → 视频按风控节奏刷 → 章节测验扫出来做掉 → 回报结果
```

- **扫描是发现，不是执行**：扫描会遍历该账号**所有课程的所有章节**，不受刷课页勾选限制——
  只有这样才知道「这门课还差什么」。勾选只决定点「开始」后做哪些。
- **首页回答两件事**：全绿了几门 / 还差几个任务点；以及哪些必须你本人出手。
- **缺口不算失败**：人脸、滑块、位置签到要到教室、考试、需要上传的作业、未开放章节，
  单独列成「需要你本人」，不伪装成已完成，也不计入「还差几个任务点」。

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
| 章节测验自动答题 | 挂机模式：巡检到新测验就拉题 → 字体反解 → 查答案 → 作答 → 按节奏提交，全程不用管 |
| 题库 / 大模型答题 | 题库优先（tikuAdapter / 言溪 / 柠檬），没命中再走 DeepSeek 等 OpenAI 兼容接口；也可以只用大模型 |
| 答题设置 | 题库端点与 token、大模型 key / baseUrl / model、只暂存开关、节奏参数，都在「章节测验 → 题库/风控」里 |

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

视频任务点与章节测验都已实现，能串成上面那条流水线。文档、阅读、直播、章节学习次数、
需要上传的作业、考试题不做；音频任务也未单独适配——这些会以「需要你本人」的形式出现在首页。

## 测试

```bash
./gradlew test
```

覆盖协议解析、字体反解、答题结果判定之外，还有几条专门盯 UI 崩溃的回归：

- `MainActivitySmokeTest`：启动冒烟，断言 `activity_main` 能 inflate、底栏不超过 5 项（曾经 6 项直接启动崩溃）
- `ViewDestroyedSmokeTest`：任务跑着时销毁视图，再调用异步回调会碰到的那些方法，断言不抛 NPE
- `UiFontPolicyTest`：断言 UI 没有写死字体（界面文字必须跟随系统字体）
- `CourseAuditTest`：「全绿」的判定口径

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
├── MainActivity.kt             底部五 Tab 壳（首页 / 账号 / 签到 / 刷课 / 测验），日志为二级页
├── MonitorService.kt           前台服务：常驻巡检 + 通知
├── study/                      刷课（仅视频）
│   ├── StudyApi.kt             课程/章节/任务点/视频进度接口
│   ├── StudyParser.kt          jsoup 解析课程、章节、视频卡片
│   ├── StudyEngine.kt          视频任务编排（并发/倍速/403 恢复/重试）
│   ├── StudyManager.kt         刷课状态中心 + 任务列表
│   ├── StudyCheckStore.kt      任务点检查结果本地缓存（按账号）
│   ├── StudyCourseStore.kt     课程/章节列表本地缓存（按账号）
│   ├── StudyService.kt         刷课前台服务 + 通知 + 唤醒锁
│   ├── CourseAudit.kt          「这门课还差什么」：任务点缺口聚合（纯逻辑，可单测）
│   ├── CourseAuditor.kt        真机体检：逐章检查任务点，得出视频/测验/需本人缺口
│   ├── CourseAuditStore.kt     体检结果缓存（首页据此显示还差几个任务点）
├── work/                      章节测验（自动答题）
│   ├── WorkApi.kt             取题 / 提交 / 回查接口
│   ├── WorkParser.kt          题量、题型、选项、表单字段、密文字体
│   ├── FontDecoder.kt         字体反解（纯 JVM 字形相似度）
│   ├── AndroidGlyphSource.kt  Android 字形渲染
│   ├── AnswerProvider.kt      题库 / 大模型三种模式
│   ├── LlmClient.kt           OpenAI 兼容客户端 + 格式校验
│   ├── QuizEngine.kt          单卷答题编排（节奏 / 提交 / 回查）
│   ├── QuizAutoRunner.kt      挂机巡检
│   └── QuizHandledStore.kt    已处理记录
├── ui/
│   ├── StudyFragment.kt          刷课页
│   ├── StudyDetailActivity.kt    任务详情页
│   ├── QuizFragment.kt           章节测验页（全课程扫描 / 勾选 / 挂机开关）
│   ├── HomeFragment.kt           首页结果面板（全绿进度 / 需你本人 / 停止）
│   ├── QuizAdapter.kt            测验列表
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

- 刷课只处理视频任务点；章节测验走单独的「章节测验」页，文档、阅读、直播、章节学习次数不在范围内；
- 章节测验只做单选 / 多选 / 判断 / 填空 / 简答；连线、排序、组合题以及 `type≥5` 的未知题型直接留空；
- 字体反解相似度低于 0.86 的题会留空待复核；题库和大模型都没命中也留空，不猜；
- 人脸识别 / 滑块验证 / 需要签到码的测验只能人工，App 会标「需人工」并推送通知，不中断其它卷；
- 题库与大模型都是第三方服务，挂了只影响命中率，不影响提交；
- 课程/章节缓存不会自动过期；课程内容有变化时点顶部「刷新课程」或课程卡片「刷新」；
- 任务点检查依赖服务端 `knowledge/cards` 的 `isPassed` 字段；字段缺失时会按未完成处理，可点课程卡片「刷新」重新检查；
- 服务端可能返回“播放位置已到末尾但 `isPassed=false`”的假进度，此时按原项目逻辑持续发心跳，直到服务端通过；末尾最多等待 3 分钟，超时按失败处理；
- 登录可能触发图形验证码 / 风控，此时需要换个时间重试；
- 账号密码以明文保存在 App 私有目录（`SharedPreferences`），未做 Keystore 加密；
- 未实现环信 IM 推送（真正的秒级零轮询方案）；
- 位置签到若无教师坐标且未手动设置，会直接报「拿不到定位」并跳过，不再发 `-1,-1`；
- 挂机答题默认开启、默认正式交卷，不想自动交可以在「题库/风控」里打开「只暂存」。

## 许可

本项目以 GPL-3.0 发布。视频刷课协议参考 `Samueli924/chaoxing`，详见 `NOTICE` 与 `LICENSE`。

## 提醒

仅供个人账号的自动化使用。批量代签涉及校规与平台服务条款风险，请自行判断。
