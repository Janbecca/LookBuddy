import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const backendUrl = process.env.LOOKBUDDY_BACKEND_URL || env.LOOKBUDDY_BACKEND_URL || 'http://127.0.0.1:8000';
  return {
    plugins: [react()],
    server: {
      port: 5173,
      proxy: {
        '/v1': backendUrl,
        '/health': backendUrl,
        '/openapi-api': {
          target: 'https://openapi-us.eufylife.com',
          changeOrigin: true,
          secure: true,
          rewrite: (path) => path.replace(/^\/openapi-api/, ''),
        },
      },
    },
  };
});

