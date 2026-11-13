import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: {
      '/order-api': {
        target: 'http://127.0.0.1:18081',
        changeOrigin: false,
        rewrite: (path) => path.replace(/^\/order-api/, ''),
      },
      '/foundation-api': {
        target: 'http://127.0.0.1:18080',
        changeOrigin: false,
        rewrite: (path) => path.replace(/^\/foundation-api/, ''),
      },
    },
  },
  preview: { host: '127.0.0.1', port: 4173, strictPort: true },
})
