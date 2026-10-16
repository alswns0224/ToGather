import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // Slack OAuth는 https만 허용하므로 ngrok 주소로 접속할 수 있게 한다. (README "외부 서비스 앱 등록")
    allowedHosts: ['.ngrok-free.app', '.ngrok-free.dev', '.ngrok.app', '.ngrok.dev'],
    proxy: {
      // 개발 중 /api 요청은 Spring Boot(8080)로 전달
      '/api': 'http://localhost:8080',
    },
  },
});
