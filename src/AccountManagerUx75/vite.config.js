import { defineConfig } from 'vite';
import basicSsl from '@vitejs/plugin-basic-ssl';

export default defineConfig({
  root: '.',
  plugins: [basicSsl()],
  server: {
    port: 8899,
    proxy: {
      '/AccountManagerService7/wss': {
        target: 'wss://localhost:8443',
        ws: true,
        secure: false
      },
      '/AccountManagerService7': {
        target: 'https://localhost:8443',
        changeOrigin: true,
        secure: false
      }
    }
  },
  define: {
    __FEATURE_PROFILE__: JSON.stringify(process.env.VITE_FEATURE_PROFILE || 'full')
  },
  build: {
    outDir: 'dist',
    sourcemap: true
  }
});
