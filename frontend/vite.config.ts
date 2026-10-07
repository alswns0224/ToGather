import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // 개발 중 /api 요청은 Spring Boot(8080)로 전달
      '/api': 'http://localhost:8080',
    },
  },
});
