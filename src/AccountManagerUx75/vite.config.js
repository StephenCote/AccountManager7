import { defineConfig } from 'vite';

export default defineConfig({
  root: '.',
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
  build: {
    outDir: 'dist',
    sourcemap: true
  }
});
