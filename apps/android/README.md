# Android API 调试客户端

登录、配置、提醒、干预 / 表情记录、学习 / 调度解释、日记、删除与 JSON API 控制台均连接真实后端。Android 端已接入官方 eufy App SDK 2.2.7，可读取真实设备、在线状态、事件和缩略图，并在能力检查和人工二次确认后控制隐私模式。手机端不伪造姿态或表情识别；视觉感知仍由 Web / 桌面端的 eufy 裸帧链路提供。

## 构建和启动

JDK 17、Android SDK 36、min SDK 24、Gradle 8.13、AGP 8.11.1、Kotlin 2.2.21：

```powershell
cd apps/android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

APK：`app/build/outputs/apk/debug/app-debug.apk`。可用 `adb install -r` 安装到自己的测试设备。当前无已配置的 AVD / 系统镜像，未验证安装、界面操作或 Keystore 在设备上的表现。

主办方开发指南标注 SDK 本身支持 API 24，官方示例 App 使用 API 26。LookBuddy 使用 API 24 并通过完整构建检查；仍需在 Android 7.0/API 24 真机或模拟器上验证启动、SDK 初始化与核心页面。

macOS / Linux 首次执行 `chmod +x ./gradlew`，之后使用 `./gradlew`；Windows 使用
`.\gradlew.bat`。脚本保持各平台原生入口，
文本源码统一 UTF-8；不要对 AAR、APK、JAR、图片或原生 `.so` 做换行转换。

## eufy App SDK

连接页按官方 Business Login 顺序调用
`ESIotKit.start(ESIotAppConfig)` 和 `ESIotKit.setUserConfig(ESIotUserConfig)`。
需要从赛事账号侧取得：

- `appName`
- `clientId`
- `userId`
- `authorization / token`
- HTTPS `appDomain`
- HTTPS `deviceDomain`
- `presetKey`

这些值仅保留在当前进程内，不写入 SharedPreferences、日志或 LookBuddy 后端；
成功连接后 token 输入框立即清空，主动断开会清空全部输入和 SDK 会话。

设备区显示名称、型号、SN、父设备和在线状态。真实账号返回空列表时界面明确显示空状态，
不会生成演示设备。选中在线设备后可查询过去 24 小时事件并读取事件缩略图。
隐私模式只在设备在线且物模型声明支持时启用按钮，每次命令都弹出二次确认。
SDK 未确认、设备离线、能力未就绪、超时或隐私区拦截均显示失败和恢复建议。

启动后先配置后端：

- 官方模拟器访问主机：`http://10.0.2.2:8000/`。
- 真机：同一局域网主机 IP，例如 `http://192.168.1.20:8000/`；后端须按需绑定局域网地址并允许防火墙。仅用于受信调试网，不传真实敏感数据。
- 正式远端使用 HTTPS。地址只接受 origin，不接受用户密码、路径前缀、查询或 fragment。更换地址清除本地登录，避免把旧令牌发送给新后端。

Debug 允许本机 / 私有 IPv4 网段 HTTP；release 禁止明文，默认远端地址仍是无效占位，不能直接发布。已有浏览器 / 桌面账号可以直接登录，不要重复注册同邮箱。

## 操作

1. 注册 / 登录后，修改久坐 30/60/90 分钟、休息 5/10/15/20 分钟、忙碌与两个提醒开关。两端同账号以服务端偏好为准。
2. 前台连接 `/v1/stream`，Authorization 请求头携带访问令牌，不放进 URL。消息只触发补查，不把客户端消息当完整业务真相。断线 / 重连及前台每 5 秒间隔补查，后台关闭实时连接和周期轮询；没有后台通知或 Android 悬浮窗。
3. 待处理提醒先点“显示并登记提醒”，服务端预算通过后再显示正文。接受 / 稍后 10 分钟 / 忽略走同一提醒 ID，服务端幂等。其他端已处理或预算限制会显示失败并刷新，不伪造完成。
4. live / demo 分别查询干预、表情与奖励。12 种回放复用后端规则，明确标为加速模拟；学习开关只更新 demo。没有表情满意度问卷。
5. API 控制台支持同后端 `/v1/` GET/POST/PATCH/DELETE，账号接口只能用专用按钮，避免令牌进入结果框。DELETE、demo 重置与账号删除都需确认。设备 / 事件 / 观察 / 抓拍标签等其余 API 可用控制台调试；图片不上传。
6. 日记按账号时区生成；读取、编辑及删除可用控制台。退出本地立即清理会话，离线时服务器刷新令牌撤销不保证送达。失败删除不会显示成功或清理有效登录。

会话使用 Android Keystore AES-GCM 加密后存入私有偏好，禁用备份，不保存密码或输出请求日志。401 使用受锁保护的单次续期；并发 401 共享刷新，失效刷新清理登录，网络 / 5xx 保留会话，旧刷新不能恢复已退出会话。设备上的加密恢复仍待实测。

架构采用 [Android ViewModel / 状态持有指导](https://developer.android.com/topic/libraries/architecture/viewmodel)：Compose 仅调试控件；ViewModel 持有状态及生命周期操作；Repository / Retrofit 持有网络逻辑；SDK Gateway 独立于域模型。

## 验证

```powershell
$env:LOOKBUDDY_ANDROID_INTEGRATION_BASE_URL='http://127.0.0.1:8021/'
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

应先启动使用独立临时数据库的后端。集成测试创建唯一临时账号，验证配置跨客户端、12 个 demo 回放、实时消息、提醒幂等、学习隔离、日记和重置，最后删除账号。不配置环境变量时该测试跳过。

Windows JDK 17 对中文路径的 Gradle 测试 argfile 存在类加载编码风险；脚本只把非 ASCII 的测试运行时 classpath 复制到 ASCII 临时目录，不移动源文件。APK 构建仍在原项目。

尚未验收：模拟器 / 真机 UI、设备 Keystore、锁屏与后台恢复、真实赛事凭证鉴权、
T8171/T8214 设备状态/事件/缩略图/隐私模式效果、正式签名发布。自动化编译和 JVM
接口测试不替代这些真机验证；执行时填写
`docs/android-eufy-device-acceptance.md`。
