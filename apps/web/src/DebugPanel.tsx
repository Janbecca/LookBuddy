import { useEffect, useState } from 'react';
import { api } from './services/api';
import type { Preferences } from './types';
import { replayAction, type ReplayScenario } from './services/action-replay';

export function DebugPanel({ onBusyChange }: { onBusyChange?: (busy: boolean) => void }) {
  const [preferences, setPreferences] = useState<Preferences | null>(null);
  const [path, setPath] = useState('/v1/preferences');
  const [method, setMethod] = useState('GET');
  const [body, setBody] = useState('{}');
  const [result, setResult] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [scenario, setScenario] = useState<ReplayScenario>('recovery');
  const [learn, setLearn] = useState(false);

  useEffect(() => { void api.preferences().then(setPreferences).catch((cause) => setError(String(cause))); }, []);
  useEffect(() => { if (preferences) onBusyChange?.(preferences.busy); }, [preferences, onBusyChange]);
  useEffect(() => {
    const refresh = () => { void api.preferences().then(setPreferences).catch((cause) => setError(String(cause))); };
    window.addEventListener('lookbuddy:preferences-changed', refresh);
    return () => window.removeEventListener('lookbuddy:preferences-changed', refresh);
  }, []);

  async function update(patch: Partial<Omit<Preferences, 'updatedAt'>>) {
    setBusy(true);
    setError('');
    try { setPreferences(await api.updatePreferences(patch)); }
    catch (cause) { setError(String(cause)); }
    finally { setBusy(false); }
  }

  async function execute() {
    if (method === 'DELETE' && !window.confirm('将删除所选资源，确认执行？')) return;
    setBusy(true);
    setError('');
    try {
      const response = await api.debugRequest(path, method, method === 'GET' ? undefined : JSON.parse(body));
      setResult(JSON.stringify(response ?? { status: 'no content' }, null, 2));
      if (path === '/v1/preferences') setPreferences(await api.preferences());
    } catch (cause) { setError(String(cause)); }
    finally { setBusy(false); }
  }

  async function replay() {
    setBusy(true);
    setError('');
    try { setResult(JSON.stringify(await replayAction(scenario, learn), null, 2)); }
    catch (cause) { setError(String(cause)); }
    finally { setBusy(false); }
  }

  async function resetDemo() {
    if (!window.confirm('删除当前账号的模拟干预、观察和学习数据？真实数据会保留。')) return;
    setBusy(true);
    setError('');
    try { setResult(JSON.stringify(await api.debugRequest('/v1/debug/learning/reset', 'POST', {}), null, 2)); }
    catch (cause) { setError(String(cause)); }
    finally { setBusy(false); }
  }

  async function inspect(nextPath: string) {
    setPath(nextPath);
    setMethod('GET');
    setBusy(true);
    setError('');
    try { setResult(JSON.stringify(await api.debugRequest(nextPath, 'GET'), null, 2)); }
    catch (cause) { setError(String(cause)); }
    finally { setBusy(false); }
  }

  return <section className="diary-card">
    <h2>开发调试入口 · P3 表情观察与学习</h2>
    <p>表情变化是低权重观察证据，不代表真实情绪改善。模拟模型与真实模型分别存储；桌面容器内由此窗口运行唯一感知链，桌宠只显示和控制。</p>
    {preferences && <fieldset disabled={busy}>
      <label>久坐阈值 <select value={preferences.sedentaryMinutes} onChange={(event) => void update({ sedentaryMinutes: Number(event.target.value) as Preferences['sedentaryMinutes'] })}>
        {[30, 60, 90].map((value) => <option key={value} value={value}>{value} 分钟</option>)}
      </select></label>{' '}
      <label>起身休息目标 <select value={preferences.breakMinutes} onChange={(event) => void update({ breakMinutes: Number(event.target.value) as Preferences['breakMinutes'] })}>
        {[5, 10, 15, 20].map((value) => <option key={value} value={value}>{value} 分钟</option>)}
      </select></label>{' '}
      <label><input type="checkbox" checked={preferences.busy} onChange={(event) => void update({ busy: event.target.checked })} />忙碌</label>{' '}
      <label><input type="checkbox" checked={preferences.postureEnabled} onChange={(event) => void update({ postureEnabled: event.target.checked })} />姿态 / 久坐提醒</label>{' '}
      <label><input type="checkbox" checked={preferences.companionEnabled} onChange={(event) => void update({ companionEnabled: event.target.checked })} />表情状态陪伴</label>
    </fieldset>}
    <fieldset disabled={busy}>
      <legend>动作闭环模拟回放（加速时间，仅 demo 数据）</legend>
      <select aria-label="模拟场景" value={scenario} onChange={(event) => setScenario(event.target.value as ReplayScenario)}>
        <option value="recovery">姿态恢复 20 秒 + 持续观察 2 分钟</option>
        <option value="recurrence">恢复后异常持续 60 秒，判定复发</option>
        <option value="gap">后续观察缺帧，结果未知</option>
        <option value="chair">坐椅平移离开，起身未验证</option>
        <option value="break">确认起身 + 完成配置的离岗时长</option>
        <option value="return">起身离开后提前返回</option>
        <option value="ignored">模拟忽略提醒</option>
        <option value="snoozed">模拟延后（只更新时机偏好）</option>
        <option value="expression_relaxed">陪伴后表情线索放松（弱证据）</option>
        <option value="expression_tense">陪伴后紧绷线索增加（多次一致才影响频率）</option>
        <option value="expression_unchanged">表情无明显变化（不当失败）</option>
        <option value="expression_gap">表情观察缺帧（结果未知）</option>
      </select>{' '}
      <label><input type="checkbox" checked={learn} onChange={(event) => setLearn(event.target.checked)} />更新独立 demo 模型</label>{' '}
      <button onClick={() => void replay()}>运行模拟回放</button>{' '}
      <button onClick={() => void inspect('/v1/interventions?namespace=demo')}>查询模拟干预</button>{' '}
      <button onClick={() => void inspect('/v1/interventions')}>查询真实干预</button>{' '}
      <button onClick={() => void inspect('/v1/learning/states?namespace=demo')}>模拟模型参数</button>{' '}
      <button onClick={() => void inspect('/v1/learning/rewards?namespace=demo')}>模拟奖励账本</button>{' '}
      <button onClick={() => void inspect('/v1/learning/preview?namespace=demo')}>预览下一次选择</button>{' '}
      <button onClick={() => void inspect('/v1/scheduler/jobs')}>延后任务</button>{' '}
      <button onClick={() => void inspect('/v1/learning/config')}>规则与奖励定义</button>{' '}
      <button onClick={() => void inspect('/v1/expressions?namespace=demo')}>模拟表情观察</button>{' '}
      <button onClick={() => void inspect('/v1/emotions/corrections')}>抓拍自评记录</button>{' '}
      <button onClick={() => void inspect('/v1/learning/preview?category=emotion&namespace=demo')}>预览陪伴策略</button>{' '}
      <button onClick={() => void resetDemo()}>重置本账号模拟数据</button>
    </fieldset>
    <pre aria-live="polite">{result}</pre>
    <details>
      <summary>API 请求控制台</summary>
      <label>方法 <select value={method} onChange={(event) => setMethod(event.target.value)}>{['GET', 'POST', 'PATCH', 'DELETE'].map((value) => <option key={value}>{value}</option>)}</select></label>{' '}
      <label>路径 <input value={path} onChange={(event) => setPath(event.target.value)} /></label>
      {method !== 'GET' && <label>JSON 请求体 <textarea rows={6} value={body} onChange={(event) => setBody(event.target.value)} /></label>}
      <button disabled={busy} onClick={() => void execute()}>执行请求</button>
    </details>
    {error && <p className="error">{error}</p>}
  </section>;
}
