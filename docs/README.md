# LookBuddy 文档导航

这份目录只保留“现在应该看什么”。带日期的旧文档用于追溯，不代表当前实现状态。

## 建议阅读顺序

1. [当前开发进度与后续计划](development-status.md)：已经完成什么、还差什么。
2. [功能边界与双端分工](project-boundaries.md)：产品做什么、不做什么，Web/桌面与 Android 如何协作。
3. [平台与 SDK 依赖](platforms-and-sdks.md)：运行环境、SDK 版本、凭证和真机依赖。
4. [系统架构](architecture.md)：数据如何流动以及隐私边界。
5. [eufy SDK 接入说明](sdk-integration.md)：Web SDK 和 Android App SDK 的具体接入方式。

## 规范与验收

- [主办方约束基线](00-organizer-constraints.md)
- [领域事件协议](event-contract.md)
- [Android 真机验收表](android-eufy-device-acceptance.md)
- [演示运行手册](demo-runbook-20261010.md)
- [eufy 详细执行计划](development-plan-eufy-readiness-20261012.md)

## 产品与历史材料

- `product/`：原始产品需求，保留需求背景和产品判断。
- `progress/`：每个开发阶段的完成记录和验证证据。
- `archive/`：已被当前计划取代的旧计划，仅用于追溯。

## 文档维护规则

- 根目录 `README.md` 只放项目介绍、快速启动和主要入口。
- 当前状态只更新 `development-status.md`，避免多个文档出现不同数字。
- SDK 版本和二进制校验值只在 `platforms-and-sdks.md` 与对应资产 README 中维护。
- 真机未验证的能力必须明确写“待真机验证”，不能用单元测试代替现场结论。
- 文档与源码统一使用 UTF-8；普通文本使用 LF，Windows 批处理脚本使用 CRLF，二进制文件不做换行转换。

