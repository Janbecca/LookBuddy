# LookBuddy 桌面容器

Electron 调试窗口是唯一感知拥有者；悬浮桌宠只有状态展示和控制，不启动摄像头、不持有令牌、不调用业务 API。桌宠外观为各状态占位，可替换为形象资源。

## 启动

先启动后端，再在项目根目录执行：

```powershell
pnpm install
$env:LOOKBUDDY_BACKEND_URL='http://127.0.0.1:8000'
pnpm dev:desktop
```

如果 Node 下载 Electron 失败，可使用保留官方来源及 npm SHA256 校验的 Windows x64 备用安装器：

```powershell
pnpm --filter @lookbuddy/desktop install:runtime
```

Electron 固定版本见 `package.json`，运行环境需要 Node 22.12 或以上。备用安装器需系统 `curl.exe`，遵循系统代理，不关闭 TLS 或校验。

若官方大文件连接仍失败，可在该安装命令前设置 `$env:LOOKBUDDY_ELECTRON_MIRROR='npmmirror'` 使用 npm 镜像；仍以 Electron npm 包发布的 SHA256 校验，校验不通过不会安装。

## 操作及边界

- 在调试窗口登录、开始陪伴并明确允许摄像头；允许仅针对本窗口的视频，音频 / 桌宠权限拒绝。
- 点“隐藏到桌宠”或调试窗口关闭按钮会隐藏窗口，仍继续陪伴。桌宠可拖动标题区，置顶、显示状态及后端提醒文字。
- 桌宠的忙碌、暂停、接受、稍后和忽略指令由唯一调试窗口处理，走原有后端预算、调度、幂等及学习，不另建提醒逻辑。开始摄像头仍须在调试窗口主动操作。
- 暂停释放摄像头并结束工作会话；退出桌面容器或关闭桌宠退出整个进程。渲染进程异常时退出，避免桌宠留在失去控制的状态。
- 浏览器 Web 和桌面容器使用相同接口，但登录存储不同。桌宠使用独立非持久会话；不会自动同步浏览器登录。
- 主窗口隐藏时关闭 Chromium 后台节流。系统休眠 / 锁屏 / 摄像头驱动的持续取帧仍需要实测；不能保证休眠时采集。

内置服务器只监听 `127.0.0.1:5189`，提供已构建的 Web、模型及 WASM，并转发 `/v1/` / `/health` 到配置后端，不托管或打包 Python 服务。保持 HTTP origin 可复用绝对资源路径，无需修改感知算法。改变端口后登录需重新建立；端口冲突时明确失败，不连接其他进程。

可通过 `LOOKBUDDY_DESKTOP_PORT` 配置端口。后端接受本机 HTTP 或远端 HTTPS，不支持带凭证、查询参数或路径前缀的地址。远端密钥仍由后端管理。

安全配置遵循 [Electron 官方安全清单](https://www.electronjs.org/docs/latest/tutorial/security)：关闭 Node integration，开启 sandbox、contextIsolation、webSecurity；限制页面导航、新窗口、权限和网络；IPC 校验发送窗口、主 frame、origin、字段与当前提醒 ID。IPC 不传图片、令牌或完整观察记录。暂未加入代码签名或正式发布加固。

## 验证及打包

```powershell
pnpm build:web
pnpm test:desktop
pnpm smoke:desktop
pnpm package:desktop
```

冒烟模式使用隔离临时登录存储，拒绝真实摄像头；检查本地模型初始化、资源 MIME、双窗口状态与控制、权限 / IPC、隐藏及重新打开，执行完退出。生成桌宠预览到 `.smoke/pet.png`。不把该模式当真人识别验证。

另外提供 `pnpm --filter @lookbuddy/desktop smoke:http`，需要先设置 `LOOKBUDDY_BACKEND_URL` 并启动隔离测试后端。该模式强制 Chromium 合成摄像头，创建临时账号，验证隐藏窗口继续上报、单流 / 单会话、忙碌写入及暂停释放，最后删除测试账号。禁止将合成流测试解释为真人识别准确率。打包后可用 `LookBuddy.exe --smoke` 或 `--smoke-http` 执行对应检查。

Windows x64 便携构建输出 `release/LookBuddy-win32-x64/LookBuddy.exe`，必须保留同目录其他文件；不是独立单文件或安装包。构建使用 `asar` 包含 Web / 模型 / WASM；后端单独启动。已存在同名输出时不覆盖，应先保留或重命名旧版本。未签名，不作正式分发承诺。
