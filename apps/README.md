# 应用目录

- `backend/`：FastAPI 服务。业务事实、提醒策略和日记的唯一服务端来源。
- `web/`：React 主界面和本地视觉感知，也是 Electron 复用的前端。
- `desktop/`：Electron 容器、悬浮桌宠、打包和桌面冒烟测试。
- `android-local/`：Android 产品客户端及 eufy App SDK 设备能力。

各应用可以独立开发，但共用 `packages/contracts` 中的事件契约。启动顺序通常是后端、Web/桌面端，最后按需启动 Android。

