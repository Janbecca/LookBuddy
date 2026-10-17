"""Describe observed event metadata without inferring behavior from camera codes."""

CATEGORIES = {"posture": "坐姿", "sedentary": "久坐", "drink": "饮水", "fatigue": "疲劳",
              "stretch": "伸展", "break": "休息", "emotion": "情绪"}
EVENTS = {
    "memory.marked": ("memory", "主动记录"),
    "behavior.drink_detected": ("drink", "饮水"),
    "posture.bad_ended": ("posture", "坐姿"),
    "posture.bad_started": ("posture", "坐姿"),
    "behavior.stretch_completed": ("stretch", "伸展"),
    "behavior.break_completed": ("break", "休息"),
    "behavior.yawn_detected": ("fatigue", "打哈欠"),
    "behavior.eyes_closed": ("fatigue", "闭眼线索"),
    "presence.entered": ("presence", "回到工位"),
    "presence.left": ("presence", "离开工位"),
    "work.session_completed": ("work", "陪伴"),
}
OUTCOMES = {"improved": "提醒后出现改善线索", "recurred": "提醒后再次出现相关线索",
            "unknown": "本次提醒效果暂未确认", "interrupted": "本次提醒已中断",
            "ignored": "本次提醒已忽略", "snoozed": "本次提醒已稍后处理"}


def diary_event_details(event):
    category, label = EVENTS.get(event.type, (None, "设备事件" if event.type == "device.security.event" else "记录"))
    # This is an explicit product category, not an SDK detectionType/rawType code.
    supplied = event.payload.get("category")
    if category in {None, "memory"} and isinstance(supplied, str) and supplied in CATEGORIES:
        category, label = supplied, CATEGORIES[supplied]
    description = event.payload.get("description")
    if not isinstance(description, str):
        description = ""
    if not description and event.type.startswith("intervention."):
        description = OUTCOMES.get(event.type.split(".", 1)[1], "")
    note = event.payload.get("note")
    title = note if isinstance(note, str) and note.strip() else (f"{label}提醒" if event.type.startswith("intervention.") and category else label)
    if label == "记录" and title == "记录":
        title = "工位记录"
    return {"eventType": event.type, "triggerCategory": category, "triggerLabel": label,
            "title": title, "category": label, "description": description}
