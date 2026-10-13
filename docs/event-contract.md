# LookBuddy 领域事件 v1

## 设计目的

领域事件隔离 eufy Web SDK、Android App SDK、电脑摄像头和模拟器的差异。业务服务不得直接依赖 SDK 原始字段。

## 公共外壳

```json
{
  "schema": "lookbuddy.event",
  "version": 1,
  "eventId": "evt_01...",
  "type": "behavior.drink_detected",
  "deviceId": "dev_01...",
  "sessionId": "ses_01...",
  "seq": 1287,
  "occurredAt": "2026-10-04T14:31:02.183+08:00",
  "source": {
    "kind": "web_vision",
    "provider": "lookbuddy",
    "sdk": "mediapipe"
  },
  "confidence": 0.86,
  "durationMs": 1800,
  "payload": {},
  "evidence": {
    "method": "wrist_mouth_sequence"
  },
  "privacy": {
    "containsImage": false,
    "retention": "event_only"
  }
}
```

## 稳定字段

| 字段 | 作用 |
|---|---|
| `schema/version` | 兼容未来迁移 |
| `eventId` | 全局幂等键 |
| `deviceId + seq` | 设备离线补传幂等键 |
| `type` | 与 SDK 无关的业务语义 |
| `occurredAt` | 带时区的事件发生时间 |
| `source` | 标记来自 Web 视觉、Android 事件或模拟器 |
| `confidence` | 识别置信度，不用于身份识别 |
| `durationMs` | 时段事件持续时间 |
| `payload` | 类型专属事实，不放展示文案 |
| `evidence` | 可解释判据或受控媒体引用 |
| `privacy` | 图片与保留策略声明 |

## v1 事件类型

### 感知和行为

```text
presence.entered
presence.left
posture.bad_started
posture.bad_ended
behavior.drink_detected
behavior.yawn_detected
behavior.eyes_closed
behavior.break_completed
behavior.stretch_completed
work.session_completed
memory.marked
calibration.completed
device.degraded
```

### 用户反馈

```text
reminder.accepted
reminder.snoozed
reminder.ignored
diary.item_confirmed
diary.item_removed
diary.item_edited
```

### Agent 决策

```text
reminder.created
reminder.suppressed
reminder.expired
diary.candidate_selected
diary.candidate_rejected
diary.generated
```

## 约束

- `type` 使用小写点分层命名。
- `occurredAt` 必须带时区。
- `confidence` 范围为 0 到 1。
- 后端批量接口每次最多接收 100 条。
- `text/detail` 等展示文案不是事实字段。
- 原始帧、面部关键点和 SDK 凭证禁止放进 `payload`。

