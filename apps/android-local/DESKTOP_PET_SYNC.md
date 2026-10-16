# Android 与桌宠联动（debug）

桌宠工程：`桌面宠物/LookBuddy_Desktop`。它与根目录下的 `apps/desktop` 独立运行。

## 配置和启动

1. 在桌宠目录安装依赖并运行 `npm.cmd start`，默认服务端口为 5211。
2. 在 Android 工程的本机 `local.properties` 配置：

   ```properties
   lookbuddy.desktop.url=http://10.0.2.2:5211
   lookbuddy.desktop.token=填写本机桌宠的配对值
   ```

   此文件不提交 Git。配对值只在本机查看和填写，不放进聊天或文档。
   也可在构建进程设置 `LOOKBUDDY_DESKTOP_URL` / `LOOKBUDDY_DESKTOP_TOKEN`，它们优先于上述属性。

3. 重新构建并安装 debug APK。两个 Gradle 入口读取同一份 `apps/android-local/local.properties`。

未配置地址时联动关闭，不默认连接任何电脑。release 的两个联动字段固定为空，且主网络配置禁止明文；本轮未实现生产配对。

## 连接方式

| 设备 | 地址 |
|---|---|
| Android 模拟器 | `http://10.0.2.2:5211` |
| USB 真机 | 运行 `adb reverse tcp:5211 tcp:5211`，使用 `http://127.0.0.1:5211` |
| 同一 Wi-Fi 真机 | 使用电脑的私网 IPv4 地址和端口 5211；电脑需允许该端口的本地访问 |

HTTP 仅允许 debug 的私网或回环地址。不要填写带路径、查询参数、片段或用户名的地址。

## 同步行为

- 仅首页可见且 App 至少处于 STARTED 时，每两秒同步一次；心情日历、聊天、其他页和后台不发送心跳。
- Android 只调用桌宠已有的 `POST /v1/sync`，使用 `X-LookBuddy-Token`。
- 同一事件保持相同 ID；完成态、当前气泡文案随首页更新。抽屉展开时发送 `message=""`。
- 连接和读取超时均为 2.5 秒。桌宠关闭、配对值不匹配或请求失败不会阻塞云端账号、工作会话或数据操作。
- 首页提醒仍是明确标注的本地预览；聊天仍未连接服务。

## 验证边界

自动测试覆盖客户端序列化、真实 Node 服务协议、事件去重/完成态、空文案、失败返回、前后台/页面生命周期。
模拟器、USB、Wi-Fi 真机以及桌宠窗口的实际操作需分别验收；自动测试结果不代表这些设备场景已通过。
