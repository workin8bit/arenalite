import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The browser only ever talks to this origin; /api and /ws are proxied to the
// Arealite server so nothing in the client needs to know a backend hostname.
export default defineConfig({
  plugins: [react()],
  server: {
    host: '0.0.0.0',
    port: 5173,
    strictPort: false,
    allowedHosts: true,
    proxy: {
      '/api': {
        target: process.env.ARENALITE_API || 'http://127.0.0.1:3001',
        changeOrigin: true,
        ws: true,
      },
    },
  },
  build: { outDir: 'dist', sourcemap: false },
});
