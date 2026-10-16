# LookBuddy Desktop · 0.3

Windows 透明置顶桌面宠物，Electron + React + TypeScript。

## 运行

双击 `启动 LookBuddy.cmd`，或执行：

```powershell
npm.cmd install
npm.cmd start
```

需 Node.js 和 npm；首次启动脚本会安装缺失依赖。这一轮交付源码运行版，未生成独立安装包。

## 第一轮验收

1. 桌面右下角显示宠物，背景透明，普通应用前方置顶。
2. 日常只显示动图和上方气泡，没有下方工具条或提示文字。单击或右键系统托盘图标打开设置，可切换全部 5 个动作，这些动作名称只用于菜单；气泡只显示App首页实际提示，没有提示时隐藏。
3. 按住宠物实体或气泡拖动可移动位置，单击不会放大，普通拖拽只更新坐标、不改变大小。只有主动选择托盘“调整大小…”（或 Ctrl+Shift+S）进入选框模式后，拖动边框才能缩放。透明区域允许鼠标穿透。命中区域依据动作静帧与容差估计，运动边缘暂不做逐帧精确命中。
4. 托盘菜单中的“暂停动画”展示该动作首帧，“继续播放”会从动画起点重新播放；也提供上一个、下一个动作和自动轮播。
5. 所有设置入口统一在系统托盘图标中。选择“调整大小…”会临时显示选框；拖动四角或四边连续等比缩放。点击“完成”、按 Esc 或 Enter 结束，恢复仅动图和气泡的桌面外观。
6. 托盘菜单可隐藏宠物；系统托盘双击或 Ctrl+Shift+L 恢复。Ctrl+Shift+Right 切到下一个动作。
7. 从托盘菜单退出，重新打开应恢复位置、动作、大小、气泡、轮播和暂停选项。

程序运行时也会在系统托盘显示图标。托盘图标可能被 Windows 收进折叠菜单。

窗口默认边长233 DIP；旧用户当前尺寸只减半一次，保留右下角位置，七种场景共用同一窗口。手动缩放范围仍为138–920 DIP，大尺寸也受屏幕可用范围限制。缩放时宠物和气泡统一保持比例。选框显示实时百分比，提供重置；托盘菜单也可“恢复默认大小”。Ctrl+Shift+S 可显示宠物并切换调整模式。调整后自动保存大小，下次启动恢复。聚焦边框拖拽点后，右 / 上方向键放大、左 / 下方向键缩小，Shift 加速。调整模式暂停自动轮播，结束后恢复。旧版大中小设置会迁移到连续尺寸。

## 素材

从用户指定文件夹递归导入 5 个 GIF。原始文件以副本保存在 `public/pets/originals/`，名称映射在 `public/pets/manifest.json` 中。显示文件为去绿幕、裁切后的动画 WebP，保留逐帧时长，并循环播放；PNG 用于暂停。原目录不作修改。附带 MP3 和路灯 PNG 尚未接入。

重新导入需要 Python、Pillow 和 NumPy：

```powershell
python scripts/import_assets.py "C:\Users\GC\Downloads\LookBuddy设计资产\首页GIF"
```

用户偏好保存在 Electron userData 目录的 preferences.json。构建用 `npm.cmd run build`；`npm.cmd run check` 检查缩放锚点、最小最大尺寸、多屏坐标，并在真实 Electron 窗口检查资源、气泡、暂停、透明像素、缩放模式和等比尺寸变化。截图与检查报告保存到 artifacts/。

Windows 通过原生 SetWindowPos 的 NOSIZE 标记移动窗口，避免 Electron 在高 DPI 下反复换算矩形造成越点越大。置顶在显示、失焦和定时检查时恢复，恢复置顶不抢键盘焦点；托盘主动隐藏时保持隐藏。回归检查覆盖连续 12 次单击、24 次往返拖拽不改尺寸、普通模式拒绝缩放、原生窗口层级及置顶恢复。

## 本地 App 同步

### 场景与 App 首页素材一致

| 输入 scene/category | label / mood | 动画 |
| --- | --- | --- |
| drink | | 该喝水了.webp |
| sedentary / stand | | 伸展.webp |
| posture | | 坐姿调整.webp |
| fatigue | | 趴桌犯困.webp |
| emotion | joyful / good | 暖暖的表情.webp |
| emotion | possibly_stressed / possibly_low / possibly_angry | 安慰倾听用户.webp |
| morning_* | | 首次打开.webp |
| evening | | 与首页一致：home_buddy_night.png静态沙发Buddy |
| diary_ready | | 保持当前日间 / 晚间默认态 |

七张专用 WebP 与 Android HOME_REMINDER_ASSETS.json 的 Git blob SHA 一致，原字节不改；仅通过显示裁切移除透明外边距及坐姿素材上下黑边。每个事件播放一遍后保持末帧；重复 event_id 不重播，新 ID 可重播同一动作。暂停显示首帧，继续重新播放。原有五种动作保留，新动作也可从托盘手动预览。

`POST /v1/sync` 持续上报 `scene`、`event_id`、`default_scene`（idle/evening）、message（包括明确空字符串）及completed。App 首页当前提醒优先；清除提醒后回到首页当前场景。App 首页桥接已转发提醒 ID 和种类，happy/worried/stand 兼容首页的事件名。后端事件生产/传输仍沿用 App 的 HomeReminderSource 边界，桌宠不直接访问云端。

`POST /v1/reminders` 可发送 `{ "id": "event-123", "category": "emotion", "label": "possibly_low", "message": "我听着" }`，沿用下述鉴权。每次App首页快照立即覆盖临时提醒；无后续快照时，临时提醒8秒后恢复默认态；未知表情不会显示开心或担忧。`diary_ready` 为桌宠新增接收事件，后端尚未自动生产该事件。手动选动作可临时覆盖，下一次首页快照恢复同步。

素材重新导入：`python scripts/import_scenes.py <Android工程根目录>`。保留原有素材导入脚本用于旧五种动作，不应在导入场景后单独重置 manifest。

桌宠只连接同一局域网中的 Android App，不直接连接 AWS。Electron 主进程监听 `5211`，复用 `%LOCALAPPDATA%/LookBuddy/desktop-token`：

- `GET /health`：健康检查与可用局域网地址。
- `POST /v1/sync`：App 上报场景、工作状态、心情和关心语；响应返回桌宠动作、显隐、暂停等状态。
- `POST /v1/reminders`：立即显示 App 转发的提醒。
- `POST /v1/commands`：`show` / `hide` 桌宠，后台进程与托盘保持运行。
- `GET /v1/state`：读取当前双向状态快照。

除 `/health` 外均需 `X-LookBuddy-Token`。Android 模拟器使用 `http://10.0.2.2:5211`；真机与电脑连接同一 Wi-Fi 后使用启动日志中的局域网地址。Windows 防火墙只允许专用网络。

状态链路固定为：`AWS ↔ Android App ↔ 本地网络 ↔ Windows 桌宠`。桌宠工程不包含 AWS SDK、云端凭据或云端连接。

## 下一轮

在 Android 个人设置页增加桌宠在线、显隐和配对状态；然后制作安装包、开机自启动和自动更新。普通窗口置顶不保证覆盖独占全屏应用，也不保证 Windows 显示桌面时持续显示。


## 0.3 首页镜像与减半尺寸

旧尺寸467 DIP迁移为233 DIP，一次迁移后重启不再减半。全部动作共用此窗口和同一等比缩放容器，保留透明留白与气泡槽位；Windows原生边框可能有数像素取整差异。

气泡只显示`scene_message`/`message`，不以动作名、标题、连接提示或旧消息兜底。首页确认关闭、切到无提示状态或展开面板暂隐提示时，发送明确空字符串，桌宠马上隐藏气泡。同一事件ID可更新提示及完成状态，不重播。同步频率2秒，状态变化即时发出；App的完成状态也映射到末帧。日间办公WebP和晚间静态PNG均直接取自App首页，原七张场景WebP不改。

当前真机验证使用USB：`adb reverse tcp:5211 tcp:5211`，本机local.properties设置`lookbuddy.desktop.url=http://127.0.0.1:5211`，复用现有配对令牌。此连接需要保持USB调试连接；拔插或重启ADB后需要重建映射。同一Wi-Fi可将本机URL改成电脑可达的局域网地址。真实URL、令牌及local.properties不提交。

`npm run check:scenes`验证统一窗口、七个场景、提示语匹配、空提示隐藏、同ID更新、完成态与末帧；完整窗口置顶回归仍按原check单独验证。
