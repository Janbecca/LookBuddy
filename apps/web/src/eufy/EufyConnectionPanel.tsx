import { FormEvent, useState } from 'react';
import { clearEufyAuthConfig, loadEufyAuthConfig, saveEufyAuthConfig, validateEufyAuthConfig, type EufyAuthConfig } from './config';
import { loadEufyWebSdk } from './sdk-loader';
import { connect } from './openapi/connect';

export interface EufySession {
  config: EufyAuthConfig;
  integration: unknown;
}

interface Props {
  session: EufySession | null;
  onConnected: (session: EufySession) => void;
  onDisconnected: () => void;
}

export function EufyConnectionPanel({ session, onConnected, onDisconnected }: Props) {
  const [config, setConfig] = useState<EufyAuthConfig>(() => loadEufyAuthConfig());
  const [remember, setRemember] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [logs, setLogs] = useState<string[]>([]);

  const change = (key: keyof EufyAuthConfig, value: string) => setConfig((current) => ({ ...current, [key]: value }));

  async function submit(event: FormEvent) {
    event.preventDefault();
    const normalized = Object.fromEntries(Object.entries(config).map(([key, value]) => [key, typeof value === 'string' ? value.trim() : value])) as unknown as EufyAuthConfig;
    const invalid = validateEufyAuthConfig(normalized);
    if (invalid) { setError(invalid); return; }
    setBusy(true);
    setError('');
    setLogs([]);
    try {
      await loadEufyWebSdk();
      const integration = await connect(normalized, (message) => setLogs((current) => [...current, message]));
      saveEufyAuthConfig(normalized, remember);
      setConfig(normalized);
      onConnected({ config: normalized, integration });
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : String(cause));
    } finally {
      setBusy(false);
    }
  }

  if (session) return (
    <section className="diary-card">
      <div><span className="eyebrow">EUFY DEVICE</span><h2>真实设备已完成鉴权</h2></div>
      <p>{session.config.stationSn} · 凭证仅用于当前浏览器连接</p>
      <button onClick={onDisconnected}>断开设备</button>{' '}
      <button className="text-button" onClick={() => { clearEufyAuthConfig(); onDisconnected(); }}>断开并清除本地凭证</button>
    </section>
  );

  return (
    <form className="diary-card" onSubmit={submit}>
      <div><span className="eyebrow">EUFY DEVICE</span><h2>连接 T8171 / T8214</h2></div>
      <p>配置来自赛事方。默认仅保存到当前标签页会话；不要在公共电脑勾选长期保存。</p>
      <div className="eufy-config-grid">
        <label>App Name<input value={config.appName} onChange={(event) => change('appName', event.target.value)} autoComplete="off" /></label>
        <label>Client ID<input value={config.clientId} onChange={(event) => change('clientId', event.target.value)} autoComplete="off" /></label>
        <label>Client Secret<input type="password" value={config.clientSecret} onChange={(event) => change('clientSecret', event.target.value)} autoComplete="off" /></label>
        <label>Preset Key<input type="password" value={config.presetKey} onChange={(event) => change('presetKey', event.target.value)} autoComplete="off" /></label>
        <label>设备 SN<input value={config.stationSn} onChange={(event) => change('stationSn', event.target.value)} autoComplete="off" /></label>
        <label>eufy User ID<input value={config.eufyUserId} onChange={(event) => change('eufyUserId', event.target.value)} autoComplete="off" /></label>
        <label>Account UUID<input value={config.accountUuid} onChange={(event) => change('accountUuid', event.target.value)} autoComplete="off" /></label>
      </div>
      <label className="eufy-remember"><input type="checkbox" checked={remember} onChange={(event) => setRemember(event.target.checked)} /> 在这台设备长期保存凭证</label>
      {error && <p className="error">{error}</p>}
      <button className="primary" disabled={busy}>{busy ? '正在鉴权…' : '连接真实设备'}</button>
      {logs.length > 0 && <details open><summary>连接日志</summary><pre>{logs.join('\n')}</pre></details>}
    </form>
  );
}
