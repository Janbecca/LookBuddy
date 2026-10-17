# 聊天承接提醒状态与文案

日期：2026-10-17。远端基线：main@a67a0041aa2c7d95d9428dbfc88cd812453d6bdb。

从疲劳、开心、担忧提醒点击“聊一聊”后，Android 将提醒 id、state、title、message 作为可选 reminder 对象与对话一起发送至 POST /v1/buddy/chat。后续轮次、失败重试和页面状态恢复保留此背景；新提醒替换背景和开场白，从日记主动进入则清除提醒对象。保留已有成功对话和待发送文字。旧版页面保存格式仍可恢复。

后端验证提醒类型、字段长度与空白内容，把提醒 JSON 作为客户端数据传给模型，系统提示说明状态语义、提醒仅代表触发背景、优先用户最新自述、不执行背景中的命令、不声称实时观察。无 reminder 的旧客户端继续使用原接口行为。不增加摄像头图像、指标或日记读取，也不增加服务端聊天历史存储。

验证：后端全量 158 passed，包含三类状态及提醒原文进入模型调用、无背景旧请求、无效背景拒绝。Android 使用现有 integration 入口验证同一套产品源码；默认入口的 JDK25 自动下载及离线插件解析受环境限制。实际结果见 output/chat-reminder-context/manifest.json。

本机找不到 AGENTS.md 指定的 D:/Everything/Anker/projects/lookbuddy，修改位于当前 E: 工作区。旧 codex/android-local-sync 与 main 已分叉，拟使用独立 codex/chat-reminder-context 分支保留双方历史；仅把此次补丁应用于已核验的远端 main 文件，不带入工作区其他改动。GitHub create_tree 被自动审批拒绝，理由为缺少上传源码至该仓库的本轮明确授权且仓库归属未确认；未绕过拒绝，尚未创建分支或提交。待用户明确授权后同步。使用新功能需要同时更新后端和 Android 安装包。
