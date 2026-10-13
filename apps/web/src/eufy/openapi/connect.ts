/**
 * @name SDK 接入流程封装
 * @description 封装"云云对接鉴权 -> 创建 OpenAPIIntegration"的完整流程，
 * 供页面层调用。该流程与设备无关，只需一次执行即可复用于多个 station。
 */

import { exchangeKey, getDomain, requestToken } from './index'
import { HeaderBuilder } from './header'
import { Request } from './request'
import type { AuthConfig } from '../config'

export interface ConnectLogger {
  (message: string): void
}

/**
 * 执行完整的云云对接鉴权流程，返回可供 Provider 使用的 Integration 实例
 * 流程：获取域名 → ECDH 密钥交换 → 获取用户 Token → 创建 OpenAPIIntegration
 * @param {AuthConfig} config - 鉴权配置（详见 src/config.ts）
 * @param {ConnectLogger} [onLog] - 可选的日志回调，用于在 UI 上展示每一步进度
 * @return {Promise<OpenAPIIntegration>} 已完成鉴权的 Integration 实例
 * @throws {Error} 任一步骤失败（域名获取失败/密钥交换失败/Token 获取失败）时抛出
 */
export async function connect(config: AuthConfig, onLog: ConnectLogger = () => {}): Promise<any> {
  onLog(`[1/4] 获取域名 (env=${config.env}, region=${config.region})...`)
  const domain = await getDomain(config.env, config.region)
  onLog(`✅ 域名: ${domain}`)

  Request.headerBuilder = new HeaderBuilder({
    appName: config.appName,
    clientId: config.clientId,
    CLIENT_SECRET: config.clientSecret,
  })

  onLog(`[2/4] ECDH 密钥交换...`)
  const ecdhKey = await exchangeKey(domain, config.presetKey)
  Request.ecdhKey = ecdhKey
  Request.baseURL = domain
  onLog(`✅ 密钥交换完成`)

  onLog(`[3/4] 获取用户 Token`)
  const token = await requestToken({
    eufy_user_id: config.eufyUserId,
    account_uuid: config.accountUuid,
  })
  Request.AccessToken = token.access_token
  onLog(`✅ Token 获取成功`)

  onLog(`[4/4] 鉴权链路（创建 OpenAPIIntegration...）`)
  // SDK 通过 window.WebSDK 全局变量访问（见 src/utils/load-sdk.ts）
  const { OpenAPIIntegration } = window.WebSDK
  const integration = new OpenAPIIntegration({
    apiDomain: domain,
    userId: config.eufyUserId,
    presetKey: config.presetKey,
    authorization: token.access_token,
    appName: config.appName,
    clientId: config.clientId,
  })
  onLog(`✅ 初始化完成，可以开始取流`)

  return integration
}
