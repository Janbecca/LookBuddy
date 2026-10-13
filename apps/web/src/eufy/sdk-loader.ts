import * as React from 'react';
import * as ReactDOM from 'react-dom';

export interface EufyMediaFrame {
  blob: Blob;
  width: number;
  height: number;
  timestamp: number;
  capturedAt: number;
}

export interface EufyWebSdk {
  Provider: React.ComponentType<{
    config: { stationSn: string; integration: unknown };
    children: React.ReactNode;
    loadingComponent?: React.ReactNode;
  }>;
  DeviceLiveLayout: React.ComponentType<{
    deviceSn: string;
    onError?: (error: string) => void;
  }>;
  OpenAPIIntegration: new (config: Record<string, string>) => unknown;
  subscribeMediaChannelFrames: (
    stationSn: string,
    onFrame: (frame: EufyMediaFrame) => void,
    options?: {
      intervalMs?: number;
      mimeType?: 'image/jpeg' | 'image/png' | 'image/webp';
      quality?: number;
      onError?: (error: Error) => void;
    },
  ) => () => void;
  version?: string;
}

declare global {
  interface Window {
    React: typeof React;
    ReactDOM: typeof ReactDOM;
    WebSDK: EufyWebSdk;
  }
}

let loading: Promise<EufyWebSdk> | null = null;
let fetchPatched = false;

function patchOpenApiFetchForDevProxy(): void {
  if (!import.meta.env.DEV || fetchPatched) return;
  fetchPatched = true;
  const originalFetch = window.fetch.bind(window);
  window.fetch = (input: RequestInfo | URL, init?: RequestInit) => {
    const rawUrl = typeof input === 'string' || input instanceof URL ? input.toString() : input.url;
    const url = new URL(rawUrl, window.location.href);
    if (url.hostname !== 'openapi-us.eufylife.com') return originalFetch(input, init);
    const proxied = `/openapi-api${url.pathname}${url.search}`;
    if (input instanceof Request) return originalFetch(new Request(proxied, input), init);
    return originalFetch(proxied, init);
  };
}

function ready(): boolean {
  return Boolean(window.WebSDK?.Provider && window.WebSDK?.DeviceLiveLayout
    && window.WebSDK?.OpenAPIIntegration && window.WebSDK?.subscribeMediaChannelFrames);
}

export function loadEufyWebSdk(): Promise<EufyWebSdk> {
  if (ready()) return Promise.resolve(window.WebSDK);
  if (loading) return loading;
  loading = new Promise<EufyWebSdk>((resolve, reject) => {
    patchOpenApiFetchForDevProxy();
    window.React = React;
    window.ReactDOM = ReactDOM;
    const existing = document.querySelector<HTMLScriptElement>('script[data-lookbuddy-eufy-sdk]');
    const script = existing ?? document.createElement('script');
    if (!existing) {
      script.src = '/sdk/index.js';
      script.async = false;
      script.dataset.lookbuddyEufySdk = 'true';
      document.head.appendChild(script);
    }
    const deadline = Date.now() + 10_000;
    const check = () => {
      if (ready()) resolve(window.WebSDK);
      else if (Date.now() >= deadline) reject(new Error('eufy Web SDK 加载超时，请检查 /sdk/index.js'));
      else window.setTimeout(check, 200);
    };
    script.addEventListener('error', () => reject(new Error('eufy Web SDK 加载失败')), { once: true });
    check();
  }).catch((cause) => {
    loading = null;
    throw cause;
  });
  return loading;
}
