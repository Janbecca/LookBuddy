import ReactDOM from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from './App';
import './styles.css';
import { desktop } from './desktop/bridge';
import { MediaPipeVisionEngine } from './vision/mediapipe-engine';

const queryClient = new QueryClient();

ReactDOM.createRoot(document.getElementById('root')!).render(
  <QueryClientProvider client={queryClient}>
    <App />
  </QueryClientProvider>,
);

// Isolated desktop smoke checks compile the real local models without camera access.
if (desktop?.smoke && desktop.role === 'dashboard') {
  window.lookbuddyModelProbe = async () => {
    const engine = new MediaPipeVisionEngine();
    try { await engine.initialize(); return true; }
    finally { engine.close(); }
  };
}

