# 共享包

`contracts/` 保存由后端导出的 JSON Schema，用于约束多端请求、响应和 `lookbuddy.event.v1`。更新后端模型后，在项目根目录运行：

```powershell
.\apps\backend\.venv\Scripts\python.exe .\scripts\export_contracts.py
```

生成后应同时运行后端与 Web 测试，避免只更新一端。

