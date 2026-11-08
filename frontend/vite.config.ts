import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In development the browser talks only to the Vite server; it forwards the API calls to the backend,
// so there is no cross-origin request and no CORS setup is needed.
const backend = 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': backend,
      '/fakepay': backend,
    },
  },
});
