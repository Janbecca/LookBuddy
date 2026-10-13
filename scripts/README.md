# 工具脚本

| 脚本 | 用途 |
|---|---|
| `check.ps1` | Windows 下一次运行后端、脚本、Web、桌面和 Android 检查，并防止 Android minSdk 从 API 24 回退 |
| `export_contracts.py` | 从后端模型导出共享 JSON Schema |
| `demo_preflight.py` | 演示前只读检查环境、资源和服务状态 |
| `acceptance_api.py` | 后端接口验收辅助脚本 |
| `smoke_api.py` | 轻量接口冒烟 |
| `test_demo_preflight.py` | 演示预检脚本单元测试 |

脚本不得写入真实 SDK 凭证。需要环境变量时，只从当前进程或未提交的 `.env` 读取。

