# 用本地脚本验证云端数据驱动页面

脚本：`scripts/seed_product_data.py`。调用现有 API，不连接 SQLite、不修改后端、不自动创建账号、不需要重新部署云端即可使用（云端须已有产品接口）。默认 `inspect` 只读；`seed` 才写入；`restore` 恢复原值。

## 先确认你要测试哪个账号

数据通过 `user_id` 归属账号。脚本必须持有前端当前账号的有效 access token，或使用前端同一个邮箱账号登录。

- 当前产品 App 是“安装 ID + 演示输入”的账号，不是电脑 Web 的邮箱账号。
- 另建邮箱账号或只重复输入同样手机号，不能定位 App 当前账号。
- 本脚本不导出 App 的 Keystore、不读取浏览器会话、不绕过鉴权，也不提供任意 user_id 写入。
- App 当前没有授权导出入口。若尚无 App 当前账号的授权，先不要 seed；需要另行增加仅 Debug 可用的本机联调授权方式。用邮箱模式可先验证该邮箱对应的电脑页面，但不能据此宣称 App 页面联调通过。

不要把 token/密码贴到聊天、命令行参数、Git 或截图里。脚本默认隐藏输入 token；也可自行在当前进程设置 `LOOKBUDDY_TEST_ACCESS_TOKEN`，用完清除。Token 过期请重新登录取得授权，脚本不会打印服务端错误正文或保存凭据。

## 在 LookBuddy-main 根目录执行

使用后端已有 Python 环境，依赖 `httpx`/`tzdata` 已包含在后端环境中。首次先检查：

```powershell
$backend='https://sit-p-19357380370-lookbuddy-hackathon.anker-launch.com'
& .\apps\backend\.venv\Scripts\python.exe .\scripts\seed_product_data.py inspect --backend $backend --direct
```

`--direct` 只让这个脚本忽略代理环境变量，不改变系统配置；如果必须使用代理才能访问，去掉该参数。HTTP 仅接受回环地址，云端必须 HTTPS。

按提示在本机隐藏输入当前账号 access token。输出 `userId`、日期和三项当前值；核对前端账号无误后才继续。日期默认使用账号时区的今天，可用 `--day YYYY-MM-DD` 指定过去日期。

如果测试电脑端已有邮箱账号，在所有命令中增加 `--auth email`，按提示输入邮箱和隐藏的密码即可；不支持自动注册新用户。

## 写入测试值

把下面的 `usr_替换为inspect结果` 改为上一步真实 userId：

```powershell
& .\apps\backend\.venv\Scripts\python.exe .\scripts\seed_product_data.py seed --backend $backend --direct --confirm-user-id usr_替换为inspect结果 --backup .\output\product-seed\round-1.json
```

脚本先创建恢复文件，旧文件存在则拒绝覆盖；随后写入并重新读取核对：

| 后端测试值 | 观察位置 |
|---|---|
| 昵称 `[联调测试] 云端数据已更新` | App 个人设置 |
| 当天 Happy；如果原本 Happy 则改 Sad | App 心情日历 |
| 工作计划11:00–20:00；如果原本相同则改09:00–18:00 | 系统设置工作时间、首页计划上下班倒计时 |

让 App 保持前台至少30秒，必要时切回首页/设置再看。脚本输出成功意味着云端写入和读回一致，不能代替你检查手机显示。

不伪造工作会话、姿态快照、饮水/伸展指标、学习奖励、日记或 Mark。普通 demo 事件本来就会被首页过滤，不能靠灌模拟事件证明统计页面联通。

## 恢复

```powershell
& .\apps\backend\.venv\Scripts\python.exe .\scripts\seed_product_data.py restore --backend $backend --direct --confirm-user-id usr_替换为inspect结果 --backup .\output\product-seed\round-1.json
```

恢复文件不包含 token/密码，但包含账号 ID 和原值，应作为本地隐私文件保管；推荐的 `output/` 已被 Git 忽略。原来没心情记录则删除脚本新建的该日自评；原来有记录则恢复原心情。恢复的是页面值，不承诺恢复记录更新时间或撤销增量表行的创建。

脚本写入前会记录操作意图：部分失败、响应超时后也应保留备份并使用 restore，不要删备份后反复 seed。恢复前会检查当前值；若你在手机上又改过某项，它会拒绝覆盖。请在测试过程中暂停其他端编辑；接口没有跨多个请求的原子事务/CAS保证。

脚本不删除账号、不删除备份、不强行覆盖冲突。每次新一轮使用新的备份文件名，恢复前始终核对服务器和 userId。

## 验证记录

2026-10-16：脚本专项18项、后端全量101项通过。覆盖真实 FastAPI 接口和隔离 SQLite 的写入/读回/恢复、原本无记录、账号不符、备份拒绝覆盖、后续编辑冲突、部分写入后响应丢失、代理/重定向边界及授权输入保护。未对云端账号注入数据，未宣称手机页面观察已通过。
