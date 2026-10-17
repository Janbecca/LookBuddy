# DeepSeek Launch 发布准备（2026-10-17）

## 本轮交付

- 日记写作与关心语共用 `LOOKBUDDY_LLM_API_KEY`，默认官方 HTTPS 接口和 `deepseek-flash`；普通用户无需设置模型。
- 保留旧 ROUTER/GREETING 配置兼容，模型失败保持模板降级，成功来源明确标记为 `llm`。
- 短文本关闭 DeepSeek 思考模式，关心语使用 JSON Output，限制输出长度。
- 增加需登录的 `/v1/llm/status` 与容器内 `tools/check_llm.py`，均不输出凭据。配置存在不等于请求成功。
- 合入此前工作区已有并通过测试的 PostgreSQL 私有 Schema 支持，兼容当前 Launch 的 RDS 部署；不更改现有数据库或 JWT。
- 发布步骤见 [DeepSeek 部署说明](../../deploy/launch/deepseek.md)。

## 验证与边界

- 后端全量测试：129 passed。模型成功、格式错误、服务不可用均通过模拟 HTTP 验证，没有使用真实 Key。
- Web 构建、静态资源、进程重启后的账户/偏好/日记持久化及备份完整性通过；Web 29 项测试（含 12 场景 HTTP 回放）通过。隔离测试进程显式开启演示登录，线上配置未更改。
- 当前线上 `/health/ready` 返回 200；仅证明现有服务健康，不证明本轮代码已部署或模型可用。
- 未取得 Launch 管理入口或模型凭据，因此未执行线上发布，也未发送真实模型请求。
- 本机无 Docker 引擎，未验证容器镜像构建。源码包使用根目录既有 Dockerfile，端口 8080。
- 此轮只接入现有关心语和日记，不实现多轮 Buddy 聊天。

部署者需在 Launch 私密运行配置中填入 Key，使用新源码包重新发布，再检查真实请求的 `source=llm` / `generatedBy=llm`。
