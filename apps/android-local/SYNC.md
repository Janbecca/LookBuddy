# LookBuddy 本地 App 同步

本目录对应本机 `D:/Everything/Anker/projects/lookbuddy`，与仓库原有的 `apps/android` 分别维护。

同步分支：`codex/android-local-sync`。首次同步保存 2026-10-16 本机实际 App 的代码、资源、Gradle Wrapper 和截图基准，不合并两套 Android 实现。

每次完成修改：先核对远端分支是否有新提交，再修改本地工程、完成相关验证、将对应文件同步到本目录，并核对 Git blob SHA。若远端同一文件发生变化，先处理冲突，不能直接覆盖。提交后向用户报告分支、提交号和验证结果。

本次通过已授权的 GitHub 连接器同步；本机 Git 命令行连接尚未成功，不能据此假定普通 git pull/push 已配置或有后台自动同步服务。

不上传 `.env`、API Key、`local.properties`、IDE 配置、构建输出和缓存。已安装的手机或模拟器 App 需要另行构建安装才会更新。

1.2 开屏与首次注册更新：按 Figma Page 2 调整九页、内置用户字体和静态 IP，新增设置内重看入口。已构建调试 APK、通过全部 22 项单元测试，并渲染核对 11 张注册页面截图。原始字体压缩包不入库。
