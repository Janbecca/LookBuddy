import { useState } from 'react';
import type { EufySession } from './EufyConnectionPanel';

export function EufyLiveView({ session }: { session: EufySession }) {
  const { Provider, DeviceLiveLayout } = window.WebSDK;
  const [retryKey, setRetryKey] = useState(0);
  const [error, setError] = useState('');
  if (error) return (
    <div className="camera-empty">
      <span>!</span><p>eufy 直播中断：{error}</p>
      <button onClick={() => { setError(''); setRetryKey((value) => value + 1); }}>重新连接</button>
    </div>
  );
  return (
    <Provider key={retryKey} config={{ stationSn: session.config.stationSn, integration: session.integration }} loadingComponent={<div className="camera-empty"><p>设备连接中…</p></div>}>
      <DeviceLiveLayout deviceSn={session.config.stationSn} onError={setError} />
    </Provider>
  );
}
