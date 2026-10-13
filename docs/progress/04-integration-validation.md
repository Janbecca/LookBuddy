# 第 4 步：端到端联调验证

状态：后端 API 链路已通过；浏览器摄像头仍需人工授权验证  
日期：2026-10-04

## 已验证链路

真实启动 FastAPI 后运行 `scripts/smoke_api.py`，验证以下流程：

```text
注册账号
  → 创建设备
  → 创建工作会话
  → 上报 memory.marked 领域事件
  → DiaryCurator 选入主动记录
  → 模板生成日记
  → 删除账号及关联数据
```

结果：

```text
OK: auth -> device -> session -> event -> diary -> account deletion
```

## 构建验证汇总

| 模块 | 验证 | 结果 |
|---|---|---|
| Backend | `pytest -q` | 4 passed |
| Backend API | `scripts/smoke_api.py` | 通过 |
| Web | `pnpm build:web` | 通过 |
| Android | `gradlew :app:assembleDebug` | 通过 |

## 仍需人工完成

- 浏览器摄像头权限必须由用户在页面中点击授权。
- 姿态校准和饮水动作需要真人、机位和光照实测。
- Android 模拟器/真机安装及 UI 交互。
- 主办方设备、账号、Web SDK、App SDK 和 Router 的现场接入。

## 联调发现

- `email-validator` 会拒绝 `.local` 测试邮箱，演示账号改用 `example.com`。
- 中文路径对 Python editable install 和 AGP 有影响，项目已经分别采用非 editable 安装与 AGP 官方路径覆盖。

