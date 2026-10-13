# LookBuddy 系统架构

## 整体数据流

```text
eufy Web SDK / Webcam
        | 原始视频帧，只在终端内流动
        v
Web / Electron + MediaPipe
        | 语义事件、低频状态、用户反馈
        v
FastAPI Backend
  |- 认证、偏好和设备绑定
  |- 事件存储与幂等
  |- 提醒规则、展示预算和延后调度
  |- 有限情境 Thompson Sampling
  |- 动作结果与奖励账本
  |- 日记事实筛选与 Router/模板表达
        |
        +--> Web 页面
        +--> Windows 悬浮桌宠
        +--> Android 客户端

eufy Android App SDK
        | 设备状态、事件、临时缩略图、确认后的控制
        +--> Android UI
        +--> lookbuddy.event.v1（不含图片）
```

## 各层职责

### 感知层

Web 和 Electron 使用同一套 `MediaPipeVisionEngine`。输入可以是电脑 Webcam，也可以是 eufy Web SDK 的解码帧。感知层负责有限的姿态与行为信号，不负责身份识别。

### 适配层

Web 的 eufy Adapter 和 Android 的 `EufySdkGateway` 隔离官方 SDK 类型。业务层只看到 `lookbuddy.event.v1`，不会依赖 SDK 的内部对象。

### 业务层

后端先用规则判断某个提醒是否有资格出现，再用受限学习策略选择表达方式和后续间隔。展示预算负责控制频率，用户反馈和后续动作验证负责闭环。

### 文字生成层

日记 Curator 先确定允许进入日记的事实，Router 只把事实改写成自然语言。Router 不允许新增事实，也不决定提醒和奖励。

## 关键隔离

1. 原始视频、连续帧和关键点默认不离开终端。
2. SDK 凭证不进入领域事件、后端业务表或客户端日志。
3. Android 缩略图只按需显示，不写入 LookBuddy 事件和后端。
4. Web 主窗口是桌面端唯一感知拥有者；悬浮桌宠只展示状态和转发用户操作。
5. 模拟回放使用独立标记和学习状态，不能与真实设备结果混淆。

## 降级方式

| 不可用能力 | 降级方式 |
|---|---|
| eufy Web SDK/赛事账号 | 切换到 Webcam 或明确标记的模拟回放 |
| Android App SDK/设备 | 后端调试功能仍可使用，但不伪造设备与事件 |
| Router | 使用确定性中文模板 |
| WebSocket | 客户端定时补查服务端状态 |
| 设备控制失败 | 展示真实错误并允许重试，不显示伪成功 |

