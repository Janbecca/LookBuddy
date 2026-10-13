# 第 2 步：Web 框架与视觉适配层

状态：已完成首版并通过生产构建  
日期：2026-10-04

## 目标

- 建立符合主办方版本约束的 Web 工程。
- 把队友 Demo 中的模型能力迁入模块化架构，不修改原项目。
- 在官方 Web SDK 发布前使用电脑摄像头开发。

## 已实现

### 框架

- React 18、TypeScript 5、Vite 6、pnpm workspace。
- TanStack Query 和 Zustand。
- 注册/登录界面与陪伴主界面。
- 后端 API 客户端。

### 取帧层

- `FrameSource` 接口。
- `WebcamFrameSource` 已实现。
- `EufyWebSdkFrameSource` 已建立边界，等待官方示例工程和凭证。

### 视觉推理

- 从 `LookBuddy-xyh` 复制模型资产与 WASM；原目录未修改。
- FaceLandmarker 与 PoseLandmarker 本地推理。
- GPU 失败自动回退 CPU。
- 推理频率限制为约 5 fps。
- 人脸/人体在场、肩线倾斜、身体侧倾、距离基线、眨眼/闭眼、张嘴、手腕到嘴距离。
- 20 个有效样本的个人坐姿基线校准。

### 行为事件

- 在岗/离岗状态变化。
- 坏坐姿时段结束事件。
- 闭眼和哈欠疲劳线索。
- 手腕由远到近并保持的饮水动作链。
- 事件遵循 `lookbuddy.event.v1` 并批量发往后端。
- 每 10 秒发送低频状态快照，由后端决定是否提醒。

### 页面能力

- 开始/暂停陪伴。
- 坐姿校准。
- 当前在岗和模型状态。
- 提醒接受、延后和忽略。
- 生成今日工位日记。
- 明确显示画面不上云。

## 资产来源

以下资产从队友 Demo 复制，未修改源目录：

```text
LookBuddy-xyh/demo/models/*.task
LookBuddy-xyh/demo/vendor/wasm/*
```

业务代码已重新模块化，没有直接复用单页 UI 和内存状态容器。

## 验证

执行：

```powershell
pnpm install
pnpm build:web
```

结果：TypeScript 检查与 Vite 生产构建成功。主 JS 构建产物约 314 KB，gzip 后约 99 KB；模型与 WASM 作为静态资产单独加载。

## 已知边界

- 尚未得到官方 `@webiot/features` 包与鉴权参数，因此 eufy Adapter 仍为明确报错的占位实现。
- 当前只完成规则模型迁移的核心路径，实际摄像头误报率需要现场设备与机位标定。
- 浏览器账号状态暂存在 `localStorage`；正式上线应改成安全 Cookie 或平台安全存储。

## 下一步

完成 Android 壳工程编译验证和后端真实联调。

