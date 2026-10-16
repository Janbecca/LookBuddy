# eufy SDK 接入说明

本页说明代码如何接入官方 SDK。版本和运行环境请先看 [平台与 SDK 依赖](platforms-and-sdks.md)。

## Web SDK

```text
运行时填写赛事参数
  -> OpenAPI connect() 四步鉴权
  -> OpenAPIIntegration
  -> Provider + DeviceLiveLayout 显示 Live
  -> subscribeMediaChannelFrames
  -> Blob 转 ImageBitmap，只保留最新一帧
  -> MediaPipeVisionEngine 本地分析
  -> 语义事件 / 低频状态发送给后端
```

核心代码：

- `apps/web/src/eufy/config.ts`：配置校验、会话存储和清除。
- `apps/web/src/eufy/openapi/`：鉴权、ECDH、签名和请求流程。
- `apps/web/src/eufy/sdk-loader.ts`：加载主办方 UMD SDK。
- `apps/web/src/eufy/EufyConnectionPanel.tsx`：连接、错误、重连和断开。
- `apps/web/src/eufy/EufyLiveView.tsx`：Provider 与 Live 组件。
- `apps/web/src/vision/eufy-frame-source.ts`：裸帧订阅、丢弃旧帧、超时和释放。

开发环境由 Vite 将 `/openapi-api` 代理到官方 OpenAPI。生产部署必须提供等价的受控反向代理或官方允许的同源方案，不能把 Client Secret 放入自建公共代理。

当前代码支持 eufy 与 Webcam 显式切换。停止观察或组件卸载时会取消订阅并释放 `ImageBitmap`。真机还需验证首帧、主动断流、重连、不同设备型号和长时间内存稳定性。

## Android App SDK

```text
ESIotKit.start(ESIotAppConfig)
  -> ESIotKit.setUserConfig(ESIotUserConfig)
  -> 获取设备列表、型号、SN、父设备和在线状态
  -> 查询过去 24 小时事件
  -> 用户请求后读取事件缩略图
  -> capability + 在线 + 写权限检查
  -> 二次确认后控制隐私模式
```

核心代码：

- `apps/android-local/app/src/main/java/com/lookbuddy/app/sdk/EufySdkGateway.kt`：官方 SDK 封装、超时、错误映射和领域事件转换。
- `apps/android-local/app/src/main/java/com/lookbuddy/app/ProductBackend.kt`：统一后端会话、产品状态和 SDK 生命周期。
- `apps/android-local/app/src/main/java/com/lookbuddy/app/EufyCameraFeed.kt`：官方设备实时预览 Adapter。
- `apps/android-local/app/src/main/java/com/lookbuddy/app/PersonalSettingsScreen.kt`：设备配置、事件查看和受控操作入口。

SDK 事件转换为 `lookbuddy.event.v1`。原始 `MegaEvent` 只在 Gateway 内部短期缓存，SDK 类型不会进入后端契约。缩略图只在用户点击后读取并显示，不上传后端。

错误处理覆盖设备离线、能力未就绪、30 秒超时和 SDK `-1002/-1003/-1004`。真实设备效果仍需按照 [Android 真机验收表](android-eufy-device-acceptance.md) 验证。

## Router

Router 只接收已经筛选过的事实，用于生成自然语言。没有配置 Router 时使用确定性模板，因此核心提醒和日记流程不会依赖外部大模型才能运行。

