import { FormEvent, useEffect, useRef, useState } from 'react';
import { api, ApiError } from './services/api';
import { useAuth } from './store/auth';
import type { EmotionLabel, ExpressionObservation, Intervention, Reminder, VisionMetrics } from './types';
import { BehaviorEngine } from './vision/behavior-engine';
import { WebcamFrameSource, type FrameSource } from './vision/frame-source';
import { EufyWebSdkFrameSource } from './vision/eufy-frame-source';
import { EufyConnectionPanel, type EufySession } from './eufy/EufyConnectionPanel';
import { EufyLiveView } from './eufy/EufyLiveView';
import { MediaPipeVisionEngine } from './vision/mediapipe-engine';
import { DebugPanel } from './DebugPanel';
import { PostureWindow } from './vision/posture-window';
import { StandingDetector } from './vision/standing-detector';
import { SedentaryTracker } from './vision/sedentary-tracker';
import { EMOTION_LABELS } from './vision/expression-features';
import { desktop, INITIAL_DESKTOP_STATE, type DesktopCommand } from './desktop/bridge';
import { Pet } from './desktop/Pet';

function AuthScreen() {
  const setTokens = useAuth((state) => state.setTokens);
  const [mode, setMode] = useState<'login' | 'register'>('register');
  const [email, setEmail] = useState('demo@example.com');
  const [password, setPassword] = useState('lookbuddy-demo');
  const [name, setName] = useState('体验用户');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      const tokens = mode === 'register' ? await api.register(email, password, name) : await api.login(email, password);
      setTokens(tokens);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '登录失败');
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="auth-shell">
      <section className="brand-panel">
        <span className="eyebrow">LOOKBUDDY</span>
        <h1>不是监督你，<br />只是陪你照顾自己。</h1>
        <p>本地视觉理解、低打扰提醒和一篇真正属于你的工位日记。</p>
        <div className="privacy-note">画面默认不离开浏览器 · 不识别身份</div>
      </section>
      <form className="auth-card" onSubmit={submit}>
        <div className="mode-switch">
          <button type="button" className={mode === 'register' ? 'active' : ''} onClick={() => setMode('register')}>创建账号</button>
          <button type="button" className={mode === 'login' ? 'active' : ''} onClick={() => setMode('login')}>登录</button>
        </div>
        {mode === 'register' && <label>怎么称呼你<input value={name} onChange={(event) => setName(event.target.value)} /></label>}
        <label>邮箱<input type="email" value={email} onChange={(event) => setEmail(event.target.value)} /></label>
        <label>密码<input type="password" minLength={8} value={password} onChange={(event) => setPassword(event.target.value)} /></label>
        {error && <p className="error">{error}</p>}
        <button className="primary" disabled={busy}>{busy ? '请稍候…' : mode === 'register' ? '开始体验' : '回来啦'}</button>
      </form>
    </main>
  );
}

function Dashboard() {
  const user = useAuth((state) => state.user)!;
  const clearAuth = useAuth((state) => state.clear);
  const videoRef = useRef<HTMLVideoElement>(null);
  const sourceRef = useRef<FrameSource>(new WebcamFrameSource());
  const engineRef = useRef(new MediaPipeVisionEngine());
  const behaviorRef = useRef(new BehaviorEngine());
  const timerRef = useRef<number | null>(null);
  const sessionRef = useRef<{ deviceId: string; sessionId: string } | null>(null);
  const sedentaryRef = useRef(new SedentaryTracker());
  const lastSnapshotRef = useRef(0);
  const lastDrinkAtRef = useRef<string | null>(null);
  const fatigueSignalsRef = useRef<number[]>([]);
  const postureWindowRef = useRef(new PostureWindow());
  const tickingRef = useRef(false);
  const lastFrameRef = useRef(-1);
  const standingRef = useRef(new StandingDetector());
  const interventionRef = useRef<Intervention | null>(null);
  const lastSampleRef = useRef(0);
  const [actionState, setActionState] = useState<Intervention | null>(null);
  const startingRef = useRef(false);
  const lifecycleRef = useRef(0);
  const commandHandlerRef = useRef<(command: DesktopCommand) => Promise<void>>(async () => undefined);
  const [busyMode, setBusyMode] = useState(false);
  const lastExpressionRef = useRef(0);
  const [expression, setExpression] = useState<ExpressionObservation | null>(null);
  const [capture, setCapture] = useState<{ id: string; image: string; expression: ExpressionObservation; correctionId: string } | null>(null);
  const [confirmedLabel, setConfirmedLabel] = useState<EmotionLabel>('unknown');
  const [savingCapture, setSavingCapture] = useState(false);
  const captureGenerationRef = useRef(0);
  const [running, setRunning] = useState(false);
  const [status, setStatus] = useState('等待开始');
  const [metrics, setMetrics] = useState<VisionMetrics | null>(null);
  const [reminder, setReminder] = useState<Reminder | null>(null);
  const [calibrating, setCalibrating] = useState(false);
  const calibratingRef = useRef(false);
  const calibrationCountRef = useRef(0);
  const [diary, setDiary] = useState('');
  const [error, setError] = useState('');
  const [sourceMode, setSourceMode] = useState<'eufy' | 'webcam'>('eufy');
  const [eufySession, setEufySession] = useState<EufySession | null>(null);

  useEffect(() => () => {
    lifecycleRef.current += 1;
    captureGenerationRef.current += 1;
    if (timerRef.current) window.clearInterval(timerRef.current);
    void sourceRef.current.stop();
    engineRef.current.close();
    if (sessionRef.current) void api.finishSession(sessionRef.current.sessionId).catch(() => undefined);
  }, []);

  useEffect(() => {
    if (desktop?.role !== 'dashboard') return;
    void desktop.publish({ loggedIn: true, running, busy: busyMode, status,
      emotion: running ? expression?.suggestedLabel ?? 'unknown' : 'unknown',
      posture: running && metrics?.poseVisible && engineRef.current.isCalibrated ? metrics.badPosture ? 'bad' : 'normal' : 'unknown',
      reminderId: reminder?.id ?? null, message: reminder?.message ?? '',
    }).catch((cause) => console.warn('Desktop state failed', cause));
  }, [running, busyMode, status, expression, metrics, reminder]);

  useEffect(() => desktop?.onCommand((command) => { void commandHandlerRef.current(command).catch((cause) => setError(String(cause))); }), []);
  commandHandlerRef.current = async (command) => {
    if (command.type === 'pause') await stop();
    else if (command.type === 'busy') {
      const preferences = await api.updatePreferences({ busy: command.busy });
      setBusyMode(preferences.busy);
      window.dispatchEvent(new Event('lookbuddy:preferences-changed'));
    } else if (command.reminderId === reminder?.id) await handleReminder(command.type);
  };

  async function start() {
    if (!videoRef.current || startingRef.current || timerRef.current !== null) return;
    if (sourceMode === 'eufy' && !eufySession) { setError('请先连接 eufy 真实设备'); return; }
    startingRef.current = true;
    const generation = ++lifecycleRef.current;
    let createdSession: string | null = null;
    let activeEngine: MediaPipeVisionEngine | null = null;
    setError('');
    setStatus('正在准备模型…');
    sedentaryRef.current = new SedentaryTracker();
    lastSnapshotRef.current = 0;
    lastDrinkAtRef.current = null;
    fatigueSignalsRef.current = [];
    postureWindowRef.current = new PostureWindow();
    lastFrameRef.current = -1;
    standingRef.current = new StandingDetector();
    interventionRef.current = null;
    setActionState(null);
    lastSampleRef.current = 0;
    lastExpressionRef.current = 0;
    setExpression(null);
    setCapture(null);
    captureGenerationRef.current += 1;
    setSavingCapture(false);
    try {
      sourceRef.current = sourceMode === 'eufy'
        ? new EufyWebSdkFrameSource(eufySession!.config.stationSn)
        : new WebcamFrameSource();
      const device = await api.createDevice();
      if (generation !== lifecycleRef.current) return;
      const workSession = await api.createSession(device.id);
      createdSession = workSession.id;
      if (generation !== lifecycleRef.current) return;
      sessionRef.current = { deviceId: device.id, sessionId: workSession.id };
      behaviorRef.current = new BehaviorEngine();
      behaviorRef.current.configure(device.id, workSession.id);
      engineRef.current.close();
      engineRef.current = new MediaPipeVisionEngine();
      activeEngine = engineRef.current;
      await engineRef.current.initialize();
      if (generation !== lifecycleRef.current) { engineRef.current.close(); return; }
      await sourceRef.current.start(videoRef.current);
      if (generation !== lifecycleRef.current) { await sourceRef.current.stop(); return; }
      setRunning(true);
      setStatus('陪伴中');
      timerRef.current = window.setInterval(tick, 200);
    } catch (cause) {
      activeEngine?.close();
      if (generation !== lifecycleRef.current) return;
      setError(cause instanceof Error ? cause.message : '启动失败');
      setStatus('启动失败');
      await sourceRef.current.stop();
      if (sessionRef.current) await api.finishSession(sessionRef.current.sessionId).catch(() => undefined);
    } finally {
      if (generation !== lifecycleRef.current && createdSession) await api.finishSession(createdSession).catch(() => undefined);
      startingRef.current = false;
    }
  }

  async function stop() {
    lifecycleRef.current += 1;
    if (timerRef.current) window.clearInterval(timerRef.current);
    timerRef.current = null;
    calibratingRef.current = false;
    calibrationCountRef.current = 0;
    setCalibrating(false);
    const previousSession = sessionRef.current;
    sessionRef.current = null;
    setRunning(false);
    setReminder(null);
    interventionRef.current = null;
    setCapture(null);
    captureGenerationRef.current += 1;
    setSavingCapture(false);
    setStatus('已暂停');
    await sourceRef.current.stop();
    if (!startingRef.current) engineRef.current.close();
    if (previousSession) await api.finishSession(previousSession.sessionId).catch(() => undefined);
  }

  async function tick() {
    const session = sessionRef.current;
    if (!session || tickingRef.current) return;
    const generation = lifecycleRef.current;
    tickingRef.current = true;
    try {
      const now = performance.now();
      const frameToken = sourceRef.current.currentToken();
      if (frameToken === lastFrameRef.current) return;
      lastFrameRef.current = frameToken;
      const frame = sourceRef.current.currentFrame();
      const next = engineRef.current.detect(frame, now);
      const motionPresence = standingRef.current.observe(next, now);
      sedentaryRef.current.observe(now, motionPresence);
      setMetrics(next);
      if (calibratingRef.current) {
        engineRef.current.addCalibrationSample(next);
        calibrationCountRef.current += 1;
        if (calibrationCountRef.current >= 20) {
          engineRef.current.finishCalibration();
          postureWindowRef.current = new PostureWindow();
          calibrationCountRef.current = 0;
          calibratingRef.current = false;
          setCalibrating(false);
        }
      }

      postureWindowRef.current.observe(now, next.badPosture, next.poseVisible && next.faceVisible && engineRef.current.isCalibrated);

      const events = behaviorRef.current.update(next, now);
      if (events.length) {
        await api.sendEvents(events);
        if (generation !== lifecycleRef.current) return;
        for (const event of events) {
          if (event.type === 'behavior.drink_detected') lastDrinkAtRef.current = event.occurredAt;
          if (event.type === 'behavior.yawn_detected' || event.type === 'behavior.eyes_closed') fatigueSignalsRef.current.push(Date.now());
        }
      }

      if (now - lastExpressionRef.current >= 1000) {
        lastExpressionRef.current = now;
        const response = await api.sendExpression({
          expressionId: `exp_${crypto.randomUUID()}`, sessionId: session.sessionId, namespace: 'live', source: sourceRef.current.kind,
          capturedAt: next.capturedAt, cameraHealthy: true, quality: next.expression.quality, features: next.expression.features,
        });
        if (generation !== lifecycleRef.current) return;
        setExpression(response.observation);
        if (response.intervention) {
          setActionState(response.intervention);
          if (['expression_relaxed', 'expression_tense', 'expression_unchanged', 'unknown'].includes(response.intervention.status)) {
            interventionRef.current = null;
            setReminder((current) => current?.id === response.intervention!.id ? null : current);
          } else interventionRef.current = response.intervention;
        }
      }

      const activeIntervention = interventionRef.current;
      if (activeIntervention && activeIntervention.category !== 'emotion' && now - lastSampleRef.current >= 1000) {
        lastSampleRef.current = now;
        const observable = next.poseVisible && next.faceVisible && engineRef.current.isCalibrated;
        const observableAction = activeIntervention.category === 'sedentary' ? next.motion !== null : observable;
        try {
          const response = await api.sampleIntervention(activeIntervention.id, {
            frameId: crypto.randomUUID(), capturedAt: next.capturedAt, source: sourceRef.current.kind, cameraHealthy: true,
            quality: observableAction ? 'observable' : 'insufficient',
            posture: observable ? next.badPosture ? 'bad' : 'normal' : 'unknown',
            presence: motionPresence, standingConfidence: motionPresence === 'standing' ? 0.8 : 0,
          });
          if (generation !== lifecycleRef.current) return;
          setActionState(response.intervention);
          if (['improved', 'recurred', 'unknown', 'interrupted'].includes(response.intervention.status)) {
            interventionRef.current = null;
            setReminder((current) => current?.id === response.intervention.id ? null : current);
          } else interventionRef.current = response.intervention;
        } catch (cause) {
          if (cause instanceof ApiError && cause.status === 409) interventionRef.current = null;
          else throw cause;
        }
      }

      if (Date.now() - lastSnapshotRef.current >= 10_000) {
        lastSnapshotRef.current = Date.now();
        fatigueSignalsRef.current = fatigueSignalsRef.current.filter((value) => Date.now() - value < 10 * 60_000);
        const posture = postureWindowRef.current.summary(now);
        const observablePosture = next.poseVisible && next.faceVisible && engineRef.current.isCalibrated;
        const response = await api.sendSnapshot({
          sessionId: session.sessionId,
          capturedAt: next.capturedAt,
          presence: next.presence ? 'online' : 'away',
          sedentaryMs: sedentaryRef.current.sedentaryMs,
          badPostureRatio5m: observablePosture && posture.observedMs >= 30_000 ? posture.badRatio : 0,
          badPostureStreakMs: Math.round(posture.badStreakMs),
          lastDrinkAt: lastDrinkAtRef.current,
          fatigueSignals10m: fatigueSignalsRef.current.length,
        });
        if (generation !== lifecycleRef.current) return;
        if (response.intervention?.strategy === 'silent') {
          interventionRef.current = response.intervention;
          setActionState(response.intervention);
        } else if (response.reminder && response.intervention) {
          await receiveReminder(response.reminder, response.intervention);
        } else {
          const pending = await api.pendingReminders();
          if (generation !== lifecycleRef.current) return;
          if (pending.length) {
            const value = await api.intervention(pending[0].id);
            if (generation !== lifecycleRef.current) return;
            await receiveReminder(pending[0], value);
          }
        }
      }
    } catch (cause) {
      console.warn('Vision tick skipped', cause);
    } finally {
      tickingRef.current = false;
    }
  }

  async function receiveReminder(nextReminder: Reminder, value: Intervention) {
    const generation = lifecycleRef.current;
    const displayed = await api.displayIntervention(value.id);
    if (generation !== lifecycleRef.current || !sessionRef.current) return;
    if (['improved', 'unknown', 'recurred', 'interrupted', 'cancelled', 'rescheduled', 'expression_relaxed', 'expression_tense', 'expression_unchanged'].includes(displayed.status)) return;
    setReminder(nextReminder);
    setActionState(displayed);
    if (['posture', 'sedentary', 'emotion'].includes(displayed.category)) interventionRef.current = displayed;
  }

  async function handleReminder(action: 'accepted' | 'snoozed' | 'ignored') {
    if (!reminder) return;
    const generation = lifecycleRef.current;
    const reminderId = reminder.id;
    try {
      await api.respondReminder(reminderId, action);
      if (generation !== lifecycleRef.current) return;
      if (action === 'ignored') {
        interventionRef.current = null;
        if (actionState && !['improved', 'recurred', 'unknown', 'interrupted'].includes(actionState.status)) setActionState({ ...actionState, status: action });
      }
      if (action === 'snoozed' && actionState) setActionState({ ...actionState, status: 'snoozed' });
      setReminder((current) => current?.id === reminderId ? null : current);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '响应提醒失败');
    }
  }

  async function makeDiary() {
    const localDate = new Intl.DateTimeFormat('en-CA', { timeZone: user.timezone }).format(new Date());
    const result = await api.generateDiary(localDate);
    setDiary(result.content);
  }

  async function grabCapture() {
    if (!expression || !running) return;
    const generation = captureGenerationRef.current;
    try {
      const frame = sourceRef.current.currentFrame();
      const dimensions = frame instanceof HTMLVideoElement
        ? { width: frame.videoWidth, height: frame.videoHeight }
        : frame instanceof ImageBitmap
          ? { width: frame.width, height: frame.height }
          : { width: 0, height: 0 };
      if (!dimensions.width || !dimensions.height) throw new Error('当前没有可抓拍画面');
      const canvas = document.createElement('canvas');
      canvas.width = Math.min(640, dimensions.width);
      canvas.height = Math.round(canvas.width * dimensions.height / dimensions.width);
      const context = canvas.getContext('2d');
      if (!context) throw new Error('无法创建本地抓拍');
      context.drawImage(frame, 0, 0, canvas.width, canvas.height);
      const id = `cap_${crypto.randomUUID()}`;
      await api.registerCapture(id, expression.id, new Date().toISOString());
      if (generation !== captureGenerationRef.current) return;
      setCapture({ id, image: canvas.toDataURL('image/jpeg', 0.8), expression, correctionId: `cor_${crypto.randomUUID()}` });
      setConfirmedLabel(expression.suggestedLabel);
    } catch (cause) { setError(String(cause)); }
  }

  async function saveCaptureLabel() {
    if (!capture) return;
    const generation = captureGenerationRef.current;
    const captureId = capture.id;
    setSavingCapture(true);
    try {
      await api.correctEmotion(capture.id, capture.correctionId, capture.expression.suggestedLabel, confirmedLabel);
      if (generation === captureGenerationRef.current) setCapture((current) => current?.id === captureId ? null : current);
    } catch (cause) { setError(String(cause)); }
    finally { if (generation === captureGenerationRef.current) setSavingCapture(false); }
  }

  return (
    <main className="dashboard">
      <header>
        <div><span className="eyebrow">LOOKBUDDY</span><h1>下午好，{user.displayName}</h1></div>
        <div className="header-actions"><span className={`status ${running ? 'online' : ''}`}>{status}</span><button className="text-button" onClick={clearAuth}>退出</button></div>
      </header>
      <section className="diary-card">
        <div><span className="eyebrow">VIDEO SOURCE</span><h2>选择正式感知画面</h2></div>
        <label>视频源 <select value={sourceMode} disabled={running || startingRef.current} onChange={(event) => setSourceMode(event.target.value as 'eufy' | 'webcam')}>
          <option value="eufy">eufy T8171 / T8214（正式演示）</option>
          <option value="webcam">电脑 Webcam（离线开发降级）</option>
        </select></label>
        <p>{sourceMode === 'eufy' ? '真实设备裸帧仅在浏览器本地处理。' : '当前为开发降级模式，不能作为赛事真设备验收证据。'}</p>
      </section>
      {sourceMode === 'eufy' && <EufyConnectionPanel session={eufySession} onConnected={setEufySession} onDisconnected={() => {
        void stop().finally(() => setEufySession(null));
      }} />}
      {desktop?.role === 'dashboard' && <section className="diary-card">
        <p>桌面感知主窗口：隐藏后仍运行，暂停或退出后释放摄像头。桌宠不启动第二条感知链。</p>
        <button onClick={() => void desktop?.hideDashboard()}>隐藏到桌宠</button>{' '}
        <button onClick={() => void stop()}>暂停并释放摄像头</button>{' '}
        <button onClick={() => void desktop?.quit()}>退出桌面容器</button>
      </section>}
      <section className="workspace-grid">
        <div className="camera-card">
          <video ref={videoRef} muted playsInline style={{ display: sourceMode === 'webcam' ? undefined : 'none' }} />
          {sourceMode === 'eufy' && eufySession && <EufyLiveView session={eufySession} />}
          {!running && sourceMode === 'webcam' && <div className="camera-empty"><span>◌</span><p>开启摄像头后，画面只在本机处理</p></div>}
          {!running && sourceMode === 'eufy' && !eufySession && <div className="camera-empty"><span>◌</span><p>请先连接 eufy 设备</p></div>}
          <div className="camera-toolbar">
            <button className="primary" disabled={!running && sourceMode === 'eufy' && !eufySession} onClick={running ? stop : start}>{running ? '暂停陪伴' : '开始陪伴'}</button>
            <button disabled={!running || calibrating} onClick={() => { calibrationCountRef.current = 0; calibratingRef.current = true; setCalibrating(true); }}>{calibrating ? '保持自然坐姿…' : '校准坐姿'}</button>
          </div>
        </div>
        <aside className="insight-card">
          <span className="eyebrow">此刻</span>
          <div className="big-state">{metrics?.presence ? '在工位' : '未检测到你'}</div>
          <dl>
            <div><dt>坐姿</dt><dd>{!metrics ? '—' : metrics.badPosture ? '可以放松一下' : '自然'}</dd></div>
            <div><dt>人脸模型</dt><dd>{metrics?.faceVisible ? '本地运行' : '等待画面'}</dd></div>
            <div><dt>姿态模型</dt><dd>{metrics?.poseVisible ? '本地运行' : '等待画面'}</dd></div>
            <div><dt>画面上传</dt><dd>关闭</dd></div>
          </dl>
          {error && <p className="error">{error}</p>}
        </aside>
      </section>
      <section className="diary-card">
        <div><span className="eyebrow">今日工位日记</span><h2>把值得记住的留下</h2></div>
        <button onClick={makeDiary}>生成今日记录</button>
        <pre>{diary || '今天的记录会在这里出现。单次哈欠或短暂姿势变化不会直接写进日记。'}</pre>
      </section>
      <DebugPanel onBusyChange={setBusyMode} />
      <section className="diary-card"><h2>表情状态与抓拍自评</h2>
        <p>{expression ? `${EMOTION_LABELS[expression.suggestedLabel]}（状态假设，${expression.quality}，累计 ${(expression.stableDurationMs / 1000).toFixed(0)} 秒）` : '等待摄像头观察'}</p>
        <p>表情不能直接证明真实情绪。中性表情默认无法判断；抓拍图片只用于本地临时预览。</p>
        <button disabled={!running || !expression || savingCapture} onClick={() => void grabCapture()}>本地抓拍并确认 / 纠正状态</button>
        {capture && <fieldset disabled={savingCapture}>
          <img src={capture.image} width={320} alt="本地临时抓拍，仅供确认此刻状态" />
          <p>原假设：{EMOTION_LABELS[capture.expression.suggestedLabel]}</p>
          <select aria-label="抓拍状态自评" value={confirmedLabel} onChange={(event) => {
            setConfirmedLabel(event.target.value as EmotionLabel);
            setCapture((current) => current ? { ...current, correctionId: `cor_${crypto.randomUUID()}` } : current);
          }}>{Object.entries(EMOTION_LABELS).map(([label, name]) => <option key={label} value={label}>{name}</option>)}</select>{' '}
          <button onClick={() => void saveCaptureLabel()}>保存自评标签并释放图片</button>{' '}
          <button onClick={() => setCapture(null)}>取消并释放图片</button>
        </fieldset>}
      </section>
      {actionState && <section className="diary-card"><h2>动作闭环 · 摄像头观察</h2>
        <p>{actionState.category} / {actionState.status} / 策略：{actionState.strategy}</p>
        <pre>{JSON.stringify(actionState.context.verification, null, 2)}</pre>
        <details><summary>策略选择与学习依据</summary><pre>{JSON.stringify(actionState.decision, null, 2)}</pre></details>
      </section>}
      {reminder && <div className="reminder-toast">
        <span>{reminder.category}</span><p>{reminder.message}</p>
        <div><button className="primary" onClick={() => handleReminder('accepted')}>{reminder.category === 'emotion' ? '好，陪我一下' : '好，一起休息'}</button><button onClick={() => handleReminder('snoozed')}>稍后</button><button className="text-button" onClick={() => handleReminder('ignored')}>忽略</button></div>
      </div>}
    </main>
  );
}

export function App() {
  const user = useAuth((state) => state.user);
  useEffect(() => {
    if (!user && desktop?.role === 'dashboard') void desktop.publish(INITIAL_DESKTOP_STATE).catch(() => undefined);
  }, [user]);
  if (desktop?.role === 'pet') return <Pet />;
  return user ? <Dashboard /> : <AuthScreen />;
}

