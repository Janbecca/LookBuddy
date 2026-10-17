# Launch 接入 DeepSeek Flash

已实现日记写作和关心语共用一套模型配置。默认使用 DeepSeek 官方接口 `https://api.deepseek.com` 和模型 `deepseek-flash`。普通 App 用户无需配置模型。

## 平台需要设置的内容

在当前 Launch 应用的**私密运行变量**中设置以下一项，值由部署者直接填写，不发送到聊天或提交 Git：

```text
LOOKBUDDY_LLM_API_KEY=<在 Launch 私密配置中填写 DeepSeek API Key>
```

以下两项已有默认值，可不填；只有使用其他兼容网关时才覆盖：

```text
LOOKBUDDY_LLM_BASE_URL=https://api.deepseek.com
LOOKBUDDY_LLM_MODEL=deepseek-flash
```

共享 Key 存在时，日记和关心语都优先使用它。共享 Key 未配置时，保留原 ROUTER/GREETING 独立配置兼容；都未配置则使用模板。不会通过 `deepseek-flash` 这个模型名自动获得调用权限，也不会复用 Codex 订阅或 Launch 账号权限。

保留当前数据库、私有 Schema、JWT Secret、CORS 和其他既有配置，不重建数据库或更换 JWT Secret。新包包含已核对的 PostgreSQL 私有 Schema 支持，避免旧 main 的 competition 校验拒绝现有 RDS。

## 发布

使用本轮生成的源码 ZIP 更新当前应用，构建入口仍为 ZIP 根目录的 Dockerfile，端口 8080。设置私密运行变量后重新发布，使环境变量进入新进程。仓库与 ZIP 均不包含真实 Key、数据库文件或本机配置。

若平台支持从 Git 构建，也可选择已交付提交；Git 推送本身不代表 Launch 已发布成功。

## 验证

1. `/health/live`、`/health/ready` 返回 200。
2. 登录后 `GET /v1/llm/status` 显示 greeting、diary 均 `configured=true`、`model=deepseek-flash`、`configuration=shared`。这仅证明配置，不证明实际调用成功。
3. 真正调用关心语接口时成功结果 `source=llm`；新生成且包含结构化事实的日记成功结果 `generatedBy=llm`。已编辑日记不会被重写；没有事实的空日记仍返回模板，不能用于判断模型连接失败。
4. 若平台支持容器终端，可执行 `python tools/check_llm.py --request`。它发送一条不含真实用户数据的短请求，只输出是否成功，不输出 Key 或模型正文；该请求可能产生少量模型费用。
5. 额度不足、鉴权、超时或格式错误时保留模板降级，不把降级标记为 AI 成功。

此接入只启用现有关心语和日记写作，不包含尚未实现的多轮 Buddy 聊天。

## 参考

- [DeepSeek 官方接入说明](https://api-docs.deepseek.com/)
- [JSON Output](https://api-docs.deepseek.com/guides/json_mode/)
- [Thinking Mode](https://api-docs.deepseek.com/guides/thinking_mode/)

本服务为短文本调用关闭 DeepSeek 默认思考模式，并限制输出长度；关心语启用 JSON Output，以便可靠解析。
