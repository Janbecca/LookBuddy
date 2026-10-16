"""Greeting-specific facts layered over the shared Buddy persona."""
from .buddy_prompt import build_buddy_prompt

SYSTEM_PROMPT = build_buddy_prompt("proactive", output_contract='【问候数据与输出契约】\n本轮没有真实天气，weather_valid=false；不编造温度、降雨、风况和预警。\nevening_rain 没有有效降雨证据时不展示。unknown 不假定用户已到岗、在家或通勤。\n仅可靠证据能证明落座，不因计划上班或请求问候就假定落座。\n早间只作普通陪伴问候，不邀请聊天；evening 简短邀请聊聊今天。\n仅输出 JSON：{"show":true,"message":"最多30字符的关心语"}。\n不满足展示条件时输出 {"show":false,"message":""}；不输出入口字段。')
