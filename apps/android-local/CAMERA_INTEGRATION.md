# 摄像头接入

三个新增页面已接到 `LookBuddyApp`：首页右上角摄像头进入陪伴视角，底部中间进入陪伴日记，右侧进入个人设置。首页统计便当可点击顶部或上滑展开，展开后仅下滑收起；拖动跟手，松手后平滑吸附。Android 返回键从新增页面回首页；切换 Tab 保留首页展开状态和日记滚动位置。

当前字体遵循现有项目的系统字体设置。日记固定展示 2026-10-13 的 Figma 示例，页尾和详情明确标记示例；选择其他日期展示空状态，不产生虚构的个人历史。个人资料的昵称沿用 `lookbuddy_onboarding` 本地存储；账号安全暂为说明，系统设置可打开 Android 应用设置，设备入口可打开陪伴视角。

## 适配入口

接口在 `app/src/main/java/com/lookbuddy/app/CameraFeed.kt`，不绑定 USB、RTSP 或具体厂商 SDK。实现 `CameraFeed` 后，在 `MainActivity` 的 `LookBuddyStartup` 中使用 `LookBuddyApp(cameraFeed = yourCameraFeed)`；请用 `remember` 或 ViewModel 保存适配器实例，避免每次重组重新创建。

- `state: StateFlow<CameraFeedState>`：推送 Disconnected / Connecting / Live / Error；收到真实帧后再发布 Live。`posture` 和 `activity` 为可空识别结果，不提供时页面显示“待识别”。
- `Preview(modifier)`：在指定边界内渲染 `AndroidView`、SurfaceView、TextureView 或其他预览组件。适配器负责视频比例和渲染资源。
- `start()` / `stop()`：必须非阻塞、幂等。页面随宿主 START / STOP 自动调用，离开摄像头页也调用 stop，适配器应关闭采集并释放预览资源。
- `markMoment(MomentRequest(secondsBefore = 5, secondsAfter = 5))`：适配器需维护点击前 5 秒环形缓冲，并采集点击后 5 秒；确实支持且缓冲就绪时才设置 `canMark = true`。保存到 MediaStore 或持久可读本地位置后返回 `MomentResult.Saved(uri)`；不可用返回 `Unavailable(reason)`。遵守协程取消，失败时清理临时文件。

当前默认 `UnconnectedCameraFeed` 不访问硬件、不申请摄像头权限、不录制视频。预览保留设计稿房间静态画面并标注“示意画面 · 摄像头待连接”；未连接或缓冲未就绪时点击 Mark 一刻只显示提示。识别结果不会显示设计稿中的虚构实时姿态。接入真实摄像头时，应由适配器实现设备发现、鉴权和实际所需的运行时权限。

## 验证命令

在主工程目录运行，JAVA_HOME 指向 `D:/Everything/Anker/Android studio/jbr`：

```powershell
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest
./gradlew.bat :app:updateDebugScreenshotTest
```

新增 `CompanionPagesTest` 覆盖导航 / 状态恢复、日记详情、昵称取消及持久化、待连接 Mark 提示、真实适配器调用参数与停止、录制失败重试。`CompanionScreenshots.kt` 提供三个页面在标准尺寸与小屏放大字体下的截图。

Figma 节点：日记 `229:7647`，陪伴视角 `254:3924`，个人设置 `229:6935`。Figma 原始 SVG / PNG 已下载到 `assets/pages` 与 `res/drawable-nodpi`，没有运行时临时素材 URL。日记关键帧保留原始图片，按 Figma 的归一化 CROP 参数渲染。

