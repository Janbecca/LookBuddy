# LookBuddy 工位搭子

这份项目基于现有 Compose 工程，新增 Figma 的启动及首次注册流程。实际工程为 `D:/Everything/Anker/projects/lookbuddy`，GitHub 对应 `apps/android-local`；工作区其他副本仅作历史资料。请在 Android Studio 打开 `D:/Everything/Anker/projects/lookbuddy`，等待 Gradle 同步后，连接手机并点击 Run ▶。

## 已实现

1. 开屏动画：静态角色图片淡入、轻微缩放，约 2.4 秒后自动进入下一页。
2. 首次注册：打招呼 → LookBuddy 自我介绍 → 缘分不简单 → 链接设备预告 → buddy 询问 → 手机验证 → 设备连接 → 正式介绍 → 首页-早上-wyr。
3. 每个对话页的两个回复都进入下一页。Android 返回键可返回上一个注册页。
4. 昵称、手机号、验证码接受任意字符，也接受空输入。无需点击获取验证码即可登录。
5. 获取验证码仅显示本地演示反馈，没有接入短信服务。
6. 昵称页键盘“完成”及收起键盘只保留并显示昵称，只有“我们继续”按钮进入手机验证页。
7. 设备连接页使用演示设备列表，点击任意连接按钮进入正式介绍，点“嗯嗯好的”进入首页。正式介绍完成后保存首次注册状态和昵称，再次启动播放开屏后直接进入首页。
8. 晨间首页使用 Figma 原始背景、桌前角色和 SVG 图标；点击统计便当顶部或上滑可展开，展开后仅下滑收起；拖动跟随手指，松手后平滑吸附，顶部不显示灰色点击反馈，底部中间图标现进入陪伴日记。
7. 输入键盘出现或屏幕空间不足时页面可以滚动。

设备列表和统计数据目前是演示数据，点击连接不需要真实蓝牙设备。本次未接入设备 SDK。开屏和首次注册按最新 Figma 使用用户提供的离线字体，详见 FONTS.md；其他页面沿用现有字体。

## GLM 关心语气泡

首页 Buddy IP 旁展示 GLM 生成的气泡关心语。后端调用智谱开放平台标准接口（适用于自建应用），返回 `show` 和 `message`；模型失败时使用符合相同规则的固定兜底文案。天气数据第一阶段为明确标注的模拟深圳天气。

### 第一阶段（当前）

- 后端 `backend/app.py`，仅用 Python 标准库，提供 `POST /buddy/greeting`。
- GLM Base URL / Model / API Key 均通过后端 `.env` 配置，不写入代码、不进入 Android 项目。
- 使用智谱开放平台标准 API，不使用 Coding Plan 专用额度。
- 模拟深圳天气三种场景：早间已落座、早间状态未知、下班前正在下雨。
- 后端校验返回格式（`show` 为 bool、`message` 为 string、`show=true` 时非空）。
- Android 调用后端，在首页 Buddy IP 旁展示气泡；不阻塞首页加载，避免重组导致重复请求。

### 联调步骤

1. 启动后端：`cd backend; copy .env.example .env`（填入 GLM_API_KEY）→ `python app.py`，记下终端显示的局域网 IP（如 `192.168.x.x:5210`）。
2. 修改 `BuddyGreeting.kt` 中 `BuddyGreetingConfig.EMULATOR_URL`，或直接在 `MorningHomeScreen.kt` 调用 `rememberBuddyGreeting(baseUrl = "http://你的局域网IP:5210")`。
3. 手机连接与电脑相同的 Wi-Fi，安装调试 APK，打开首页即可看到气泡。
4. 模拟器无需改 IP，默认 `http://10.0.2.2:5210` 已映射到开发机 localhost。

实现：后端 `backend/app.py`；Android `app/src/main/java/com/lookbuddy/app/BuddyGreeting.kt`；气泡集成于 `MorningHomeScreen.kt`。

## 心情日历

点击晨间首页右上角日历打开暖纸弹窗。当天未记录时，显示开心、平静、有点累、低落四个心情选项；整格可点击，“有点累”使用 Figma 原始打哈欠像素表情。保存后以 400ms 竖轴翻转显示「今日宜缓 / 遇事则缓，事缓则圆」，近期七日中的今日格同步显示所选表情。

心情保存在本机 `lookbuddy_moods`，每个自然日一条。再次打开直接显示日签和已记录心情；「换个心情」可更新当天记录，签文保持不变。未选择即关闭不会写入记录。日期按设备本地日期更新，支持跨月；没有记录的历史日期显示圆点，不生成个人历史数据。签文目前沿用设计稿固定内容。

点击关闭、遮罩或 Android 返回键均可收起。保存失败留在选择面并允许重试；保存和翻签时防止重复点击，系统关闭动画时改用淡入。小屏幕支持弹窗内容滚动。陪伴日记、陪伴视角和个人设置页已接入，详见 CAMERA_INTEGRATION.md。

实现：`app/src/main/java/com/lookbuddy/app/MoodCalendar.kt`。交互及存储测试：`app/src/test/java/com/lookbuddy/app/MoodCalendarTest.kt`。新增选择面、已记录面、小屏放大字体三张 Compose 截图预览。设计节点：`179:2457`、`146:2224`。

## 重新体验首次注册

优先从「个人设置 → 系统设置 → 重新体验初次见面」进入完整流程，可返回首页并保留现有数据。仅开发调试需要彻底重置时，才在系统应用设置清除数据，或执行：

```powershell
& 'D:/Everything/Anker/Android/platform-tools/adb.exe' shell pm clear com.lookbuddy.app
```

## 文件

- `app/src/main/java/com/lookbuddy/app/OnboardingScreen.kt`：启动、对话、输入、动画及本地注册状态。
- `app/src/main/java/com/lookbuddy/app/MainActivity.kt`：应用入口。
- `app/src/main/java/com/lookbuddy/app/MorningHomeScreen.kt`：晨间首页和可展开统计便当。
- `app/src/main/res/drawable-nodpi/lookbuddy_character.png`：Figma 提供的角色原图，保持原始 PNG 和透明背景。
- `app/src/test/java/com/lookbuddy/app/OnboardingFlowTest.kt`：首次注册交互测试。
- `app/src/screenshotTest/kotlin/com/lookbuddy/app/StartupScreenshots.kt`：实际 Compose 页面截图。

## 本机编译与验证

保留原工程的 SDK、Gradle、Kotlin 和 Compose 版本。当前 SDK 位置由 `local.properties` 指向 `D:/Everything/Anker/Android`；换电脑时由 Android Studio 设置本机 SDK 路径。

```powershell
$env:JAVA_HOME='D:/Everything/Anker/Android studio/jbr'
./gradlew.bat :app:assembleDebug
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:updateDebugScreenshotTest
```

调试 APK 在 `app/build/outputs/apk/debug/app-debug.apk`。截图在 `app/src/screenshotTestDebug/reference`，可用于检查内置字体和不同屏幕尺寸下的布局。

1.1 版已验证：调试 APK 编译成功；5 个注册流程测试和 2 个统计便当交互测试通过；新增设备连接、正式介绍、晨间首页、便当展开及填写昵称后的截图已渲染并核对。Windows 下截图和本地测试工具需要英文项目路径，请使用上述 D 盘主工程运行这些命令。

从 1.0 更新时会重新展示注册流程，便于体验新增设备连接及正式介绍页面。调试 APK 可直接覆盖安装，保留本机数据。

## 设计来源

https://www.figma.com/design/MrMMdAMDddch9OUQz9NIVQ/Untitled?node-id=0-1

| 页面 | Figma 节点 |
| --- | --- |
| 开屏动画页 | 1:2 |
| 首次注册-打招呼 | 16:962 |
| 首次注册-LookBuddy自我介绍 | 16:989 |
| 首次注册-缘分不简单 | 16:1011 |
| 首次注册-链接设备预告 | 21:1039 |
| 首次注册-buddy询问 | 23:1384 |
| 首次注册-手机验证 | 23:1412 |
| 首次注册-设备连接页 | 21:1060 |
| 首次注册-正式介绍页 | 23:1246 |
| 首页-早上-wyr | 114:4399 |
| 首页-早上-wyr / 统计便当展开 | 142:2144 |

Figma 中 iPhone 状态栏和底部指示条由 Android 原生系统栏替代。1.2 开屏与注册以 Figma Page 2 为准（节点见 FONTS.md），使用同一静态 IP 原图及各页对应尺寸的图层槽位。下表保留早期设计节点用于追溯。


## 陪伴日记、陪伴视角与个人设置

三个页面现已接入主 App。首页右上角摄像头打开陪伴视角，底部中间打开陪伴日记，右侧打开个人设置。摄像头使用可替换的 CameraFeed 接口，当前默认显示待连接状态；真实设备、实时识别和录像由后续适配器接入。日记是明确标记的设计稿示例，昵称修改保存在本机。入口、接口契约和验证方式见 [CAMERA_INTEGRATION.md](CAMERA_INTEGRATION.md)。




## 1.2 开屏与首次注册

按 Figma Page 2 更新开屏和八个首次注册页面，内置字体包中的 FontQu Smile 与霞鹜文楷 Light，并使用设计稿静态 IP 原图。已注册设备会在开屏后进入首页；可从「个人设置 → 系统设置 → 重新体验初次见面」重看全部流程，随时返回首页，保留现有数据。短信和蓝牙连接仍是本地演示。图片替换入口和字体说明见 [FONTS.md](FONTS.md)。


## 1.2.1 真机启动修复

修复 diary_buddy.png 实为 GIF 动图导致开屏加载崩溃的问题；现为保留尺寸和透明背景的首帧静态 PNG，日记共用图片同步修复。Android Studio 重新 Run 即可安装修复版，无需清除应用数据。

## 1.2.2 注册页展示修复

开屏和八个首次注册页面全部使用独立的真实静态 PNG 占位图。旧版完成注册的设备更新后将重新展示完整流程一次，保留昵称和其他本地数据；完成后正常进入首页，再次启动不重复注册。也可从个人设置手动重新体验。
