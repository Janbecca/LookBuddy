export interface EufyAuthConfig {
  env: 'us';
  region: 'US';
  appName: string;
  clientId: string;
  clientSecret: string;
  presetKey: string;
  stationSn: string;
  eufyUserId: string;
  accountUuid: string;
}

// Alias retained because the organizer OpenAPI code imports this name.
export type AuthConfig = EufyAuthConfig;

export const EMPTY_EUFY_AUTH_CONFIG: EufyAuthConfig = {
  env: 'us',
  region: 'US',
  appName: '',
  clientId: '',
  clientSecret: '',
  presetKey: '',
  stationSn: '',
  eufyUserId: '',
  accountUuid: '',
};

const SESSION_KEY = 'lookbuddy:eufy-auth:session';
const PERSISTENT_KEY = 'lookbuddy:eufy-auth:persistent';

function parse(raw: string | null): EufyAuthConfig | null {
  if (!raw) return null;
  try {
    const value = { ...EMPTY_EUFY_AUTH_CONFIG, ...JSON.parse(raw), env: 'us', region: 'US' } as EufyAuthConfig;
    return value.stationSn ? value : null;
  } catch {
    return null;
  }
}

export function loadEufyAuthConfig(): EufyAuthConfig {
  return parse(sessionStorage.getItem(SESSION_KEY))
    ?? parse(localStorage.getItem(PERSISTENT_KEY))
    ?? { ...EMPTY_EUFY_AUTH_CONFIG };
}

export function saveEufyAuthConfig(config: EufyAuthConfig, remember: boolean): void {
  const serialized = JSON.stringify({ ...config, env: 'us', region: 'US' });
  sessionStorage.setItem(SESSION_KEY, serialized);
  if (remember) localStorage.setItem(PERSISTENT_KEY, serialized);
  else localStorage.removeItem(PERSISTENT_KEY);
}

export function clearEufyAuthConfig(): void {
  sessionStorage.removeItem(SESSION_KEY);
  localStorage.removeItem(PERSISTENT_KEY);
}

export function validateEufyAuthConfig(config: EufyAuthConfig): string | null {
  const required: Array<[keyof EufyAuthConfig, string]> = [
    ['appName', 'App Name'], ['clientId', 'Client ID'], ['clientSecret', 'Client Secret'],
    ['presetKey', 'Preset Key'], ['stationSn', '设备 SN'], ['eufyUserId', 'eufy User ID'],
    ['accountUuid', 'Account UUID'],
  ];
  const missing = required.find(([key]) => !config[key].trim());
  if (missing) return `请填写 ${missing[1]}`;
  if (!/^(T8171|T8214)/i.test(config.stationSn.trim())) return '本赛题仅支持 T8171 或 T8214 设备';
  return null;
}
