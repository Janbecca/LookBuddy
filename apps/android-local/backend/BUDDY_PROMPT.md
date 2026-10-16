# Buddy 统一人格与场景规则

唯一的共享源为 `src/lookbuddy/buddy_prompt.py`：人格、事实边界、主动提示、聊天、每次输出前的语病与人格检查。问候专用天气/在岗边界由 `greeting_prompt.py` 在共享 Prompt 上叠加。

## 场景与输出

| 场景 | 提示语 | `chatEntry` |
| --- | --- | --- |
| 饮水、久坐、坐姿、早间问候 | 不引导聊天 | `null` |
| 疲劳 | 轻轻建议休息，并邀请表达 | 疲劳聊天入口 |
| `joyful` / `good` 表情线索 | 邀请分享，不断言发生好事 | 开心聊天入口 |
| `possibly_stressed` / `possibly_low` / `possibly_angry` | 温和邀请，不认定用户情绪 | 关心聊天入口 |
| `evening` 晚间问候 | 邀请聊聊今天，不默认已下班 | 晚间聊天入口 |
| 未知/专注表情、静默、被抑制、无天气证据的雨天问候 | 不增加聊天邀请 | `null` |

主动提示最多 **30 个字符，包含标点、空格、字母与数字**。固定话术已人工校读；动态问候要求模型在内部检查语言和人格。生成结果为空、换行、超长或格式错误时采用完整回退句，不直接截断，避免病句。语病和人格的模型自检不是程序可证明的语义保证，仍可通过后续评测迭代。

事件提醒当前沿用规则驱动的固定话术，不增加模型请求，不改触发、策略、频控、忙碌/离岗及静默规则。稍后到期会重新使用当前场景及表情线索生成短句，并保留对应入口信息。

## 前端契约

快照中的 `reminder`、待处理提醒、提醒响应和 WebSocket `reminder.created.data` 均通过 `ReminderPublic` 返回新增的 `chatEntry`。问候接口（包括兼容路径）也返回同一字段。

```json
{
  "message": "歇一小会儿吧，想聊聊的话我听着。",
  "chatEntry": {
    "label": "聊一聊",
    "action": "open_chat",
    "scene": "fatigue"
  }
}
```

`scene` 为 `fatigue`、`positive_expression`、`concern_expression` 或 `evening`。无入口时为 `null`。前端根据该字段在提醒气泡内显示入口，不根据文案关键词猜测。点击表示进入聊天，不等于接受提醒或完成休息等动作。

本次只提供后端入口标记与 Prompt 封装；尚未增加 Android 按钮、聊天页面或聊天 API。`open_chat` 是前端待接入的动作标识，不能当作已存在的服务端接口。

## 后续聊天复用

```python
from lookbuddy.buddy_prompt import build_buddy_prompt

messages = [
    {"role": "system", "content": build_buddy_prompt("chat")},
    # 将真实对话历史按 role 追加，不拼进 system。
    {"role": "user", "content": "今天的会开得我很烦。"},
]
```

聊天不设30字或其他固定字数上限，以真实口语的自然长度回应。温和引导倾诉、逐步理解，再提供贴合处境的建议，不强行每轮提问或过早说教。

若后续把事件提醒改为模型生成，复用 `build_reminder_messages(...)`，并验证返回类型、30字符上限和静默条件；入口继续由 `chat_entry(...)` 决定。所有 Prompt 的模型输出都要求内部先检查语病和 Buddy 形象，只返回最终内容。
