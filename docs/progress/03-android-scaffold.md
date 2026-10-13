# 第 3 步：Android 客户端与 eufy App SDK

状态：代码与自动化验证完成，等待真实设备验收  
首次记录：2026-10-04  
最近更新：2026-10-13

## 当前基线

- JDK 17、Kotlin 2.2.21。
- Android Gradle Plugin 8.11.1、Gradle 8.13。
- `compileSdk=36`、`targetSdk=36`、`minSdk=24`。主办方 SDK 标称支持 24，示例 App 使用 26。
- Jetpack Compose + Material 3。
- 官方 `ESIotKit-2.2.7.aar` 及其运行时依赖。
- Windows 使用 `gradlew.bat`；macOS/Linux 使用 LF 格式的 `gradlew`。

## 已实现

- 后端认证、配置、提醒、干预闭环、日记、数据删除和调试控制台。
- SDK 凭证仅存当前进程，不写 SharedPreferences、日志或后端。
- 按官方 Business Login 顺序执行 `ESIotKit.start` / `setUserConfig`。
- 读取真实设备名称、型号、SN、父设备和在线状态。
- 查询过去 24 小时事件，并映射为 `lookbuddy.event` v1 领域事件。
- 通过 App SDK 读取事件缩略图；SDK 图片路径不进入领域事件或后端。
- 隐私模式 capability、在线和可写检查，控制前二次确认。
- `-1002/-1003/-1004`、30 秒超时、离线与真实空列表提示。
- SDK 类型仅存在于 Gateway 实现内，不泄漏到 ViewModel 和业务 UI。

## 工具链与跨平台处理

1. 中文父目录继续通过 `android.overridePathCheck=true` 支持 Windows 构建。
2. 官方 AAR 使用 Kotlin 2.2 元数据，必须采用与示例一致的 AGP 8.11.1 /
   Gradle 8.13，避免旧 D8 元数据重写和内存溢出。
3. AAR、APK、JAR、图片和 `.so` 均视为二进制，不执行换行转换。
4. Unix `gradlew` 保持 UTF-8、LF、无 BOM；Windows 使用独立 `gradlew.bat`。
5. SDK 依赖版本和冲突强制项与官方示例保持一致，不盲目升级 Lint 提示的新版本。

## 验证结果

```powershell
cd apps/android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

- Build successful。
- 12 项单测通过。
- 1 项真实后端集成测试因未配置地址跳过。
- Android Lint：0 error。
- APK：`app/build/outputs/apk/debug/app-debug.apk`。
- 最终 APK 与合并 Manifest 均声明 `minSdkVersion=24`、`targetSdkVersion=36`；
  API 24 下的 AAR 元数据检查、Manifest 合并、D8/L8 脱糖和打包全部通过。

## 尚待验收

- 使用赛事真实凭证完成鉴权。
- 在 Android 7.0/API 24 真机或模拟器验证启动、SDK 初始化和核心页面。
- 在 T8171/T8214 上核对在线状态、真实事件与缩略图。
- 二次确认后执行一次隐私模式控制，并观察设备状态变化。
- 连续运行 15 分钟及断网/离线/超时恢复。
- 正式签名 APK。

真机结果填写 `docs/android-eufy-device-acceptance.md`；自动化测试不替代硬件验收。
