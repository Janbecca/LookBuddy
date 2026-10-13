import { useEffect, useState } from 'react';
import { desktop, INITIAL_DESKTOP_STATE, type DesktopCommand } from './bridge';
import { EMOTION_LABELS } from '../vision/expression-features';
import './pet.css';

export function Pet() {
  const [state, setState] = useState(INITIAL_DESKTOP_STATE);
  const [error, setError] = useState('');
  const [pending, setPending] = useState(false);
  useEffect(() => {
    document.body.classList.add('pet-surface');
    document.documentElement.classList.add('pet-surface');
    let active = true;
    const unsubscribe = desktop?.onState((next) => { if (active) setState(next); });
    void desktop?.state().then((next) => { if (active) setState(next); }).catch((cause) => { if (active) setError(String(cause)); });
    return () => { active = false; unsubscribe?.(); document.body.classList.remove('pet-surface'); document.documentElement.classList.remove('pet-surface'); };
  }, []);
  async function command(value: DesktopCommand) {
    setPending(true); setError('');
    try { await desktop?.command(value); }
    catch (cause) { setError(String(cause)); }
    finally { setPending(false); }
  }
  const label = !state.loggedIn ? '等待登录' : !state.running ? '已暂停' : state.busy ? '忙碌中' : state.posture === 'bad' ? '坐姿待调整' : EMOTION_LABELS[state.emotion];
  return <main className="pet-shell">
    <header className="pet-drag">LookBuddy · 拖动这里</header>
    <div className={`pet-placeholder pet-${state.emotion}`} aria-label={`桌宠占位形象：${label}`}>
      <span>◉ ◉</span><small>{label}</small>
    </div>
    <section className="pet-message" aria-live="polite">
      <p>{state.message || state.status}</p>
      <small>{state.running ? '摄像头本地运行 · 表情仅为假设' : '摄像头未运行'}</small>
    </section>
    <div className="pet-controls">
      {state.reminderId && <fieldset disabled={pending}>
        {(['accepted', 'snoozed', 'ignored'] as const).map((type, index) => <button key={type} onClick={() => void command({ type, reminderId: state.reminderId! })}>{['接受', '稍后', '忽略'][index]}</button>)}
      </fieldset>}
      <button onClick={() => void desktop?.showDashboard()}>打开调试</button>
      <button disabled={!state.loggedIn || pending} onClick={() => void command({ type: 'busy', busy: !state.busy })}>{state.busy ? '结束忙碌' : '忙碌'}</button>
      <button disabled={!state.loggedIn || pending} onClick={() => void command({ type: 'pause' })}>暂停</button>
      <button onClick={() => void desktop?.quit()}>退出</button>
    </div>
    {error && <p role="alert">{error}</p>}
  </main>;
}
