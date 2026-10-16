# 工位日记：设备历史图片与录像

## 数据与隐私

日记可在用户主动查看时通过 Android eufy SDK 读取设备已有图片与录像。LookBuddy 后端仅保存设备事件及媒体引用，不接收图片 Base64、视频文件、设备媒体路径、临时播放 URL 或 SDK 凭证。临时图片仅在预览期间存在于内存，退出预览、切换账号或应用进入后台后释放。录像默认静音，后台或离页停止并释放；返回前台重新定位媒体。

这项功能不要求开启新的录像采集。设备是否录制及历史保存期限由设备和服务决定；没有录像、录像过期或当前 SDK 账号没有权限时不能回看。LookBuddy 账号与 eufy SDK 账号独立，实际媒体访问权限由 SDK 再次校验。

## 存储

- `media_assets`：用户、后端设备 ID、设备事件 ID、事件内媒体 key、类型、角色、采集时间、时长、访问方式、最近已知状态和期限。引用唯一键为用户/设备/设备事件/媒体 key。图片路径只在本机哈希，不上传路径。
- `event_media_links`：事件与媒体的多对多关联及展示顺序。解除关联使用 `active=false`，同步不自动恢复；显式 PUT 可恢复。
- 媒体事件继续使用 `domain_events`，类型为 `device.security.event`。专用同步入口不经过提醒、干预或学习流程，日记 Curator 不把设备事件当健康行为。
- 迁移 `20261017_diary_device_media` 是新增表迁移，复用现有 additive migration 机制，不改变已有表列；SQLite/PostgreSQL 均使用 SQLAlchemy 表定义。用户/设备/事件删除通过外键清理媒体或关联。

## 接口

所有接口使用现有 LookBuddy 登录鉴权；设备 ID 必须是当前用户绑定的后端 ID，不是设备 SN。

`POST /v1/diary-device-events/batch`，每批最多 100 个事件，同一批原子提交，按设备事件身份幂等。相同 ID 的时间或媒体类型冲突返回 409，其他用户设备返回 404，未知字段和媒体内容字段返回 422。

```json
{"events":[{"deviceId":"dev_example","providerEventId":"sdk-event-id","occurredAt":"2026-10-17T06:32:00Z","mediaItems":[{"providerMediaKey":"thumbnail","kind":"image","role":"thumbnail"},{"providerMediaKey":"video","kind":"video","role":"clip"}]}]}
```

返回每条记录的后端 `eventId` 和 `mediaIds`。仅同步 SDK 明确返回的媒体，不从缩略图推断录像。SDK 未提供可靠可用期限时 `expiresAt=null`，尚未实际读取的引用 `availability=unknown`；网络失败不会标记为过期。

`GET /v1/diary-page/{day}` 每条 entry 增加 `mediaItems`，包括 `providerDeviceRef`、`providerEventId`、`providerMediaKey`、`capturedAt`、类型及状态。数据库一次批量读取媒体关联。旧字段 `media=not_recorded` 暂保留，表示旧的项目自录制能力仍未启用；新客户端以 `mediaItems` 为准。

`PUT /v1/events/{eventId}/media/{mediaId}` 显式关联或恢复。

`DELETE /v1/events/{eventId}/media/{mediaId}` 解除关联，不删除设备媒体。两者均幂等，拒绝跨用户及模拟事件关联。

## Android

进入真实日记先读取后端数据；已连接 SDK 时按用户时区同步所选日期的设备事件元数据，成功后刷新。提供手动重试，失败不阻塞文字日记。首次同步对 SDK 当前账号下有事件的设备建立后端映射；后端设备 ID 与 SN 分开。

图片及视频均点击后才读取。SDK 按时间范围和设备过滤，按事件 ID 定位；不依赖上次进程的事件内存缓存。分页使用 EventCondition count 与 lastEventTime，检测不前进/上限并报错，避免默默截断。按日边界使用本地时区转换成 UTC，跨日和夏令时使用下一天起点。

图片使用官方 `getEventImageBase64`；录像使用主办方示例中的 `EventMediaPlayer(MegaEvent, MegaDevice)`、`initVideoView`、`startPlay`、`stopPlay`、`release`，预览容器创建后推迟一帧启动。图片预览限制输入大小和解码分辨率。媒体读取与同步检查账号/后端/SDK连接代际，旧任务不能把媒体同步到新账号。

手动 Mark 仍只记录时间和备注，可从当天已有设备媒体中显式选择关联。不自动按相近时间匹配，不提供不存在的“标记前后录像”。删除日记正文保留时间线；删除关联保留设备原件。

## 验收边界

自动测试应覆盖同步重试、关联解除后重同步、跨用户拒绝、整批回滚、账号级清理、旧库迁移与原有日记流程。Android 编译和模拟测试不代表赛事设备实测：真实历史事件分页完整性、重启后图片读取、设备权限、录像播放及后台释放还需已授权真机和设备账号验收。
