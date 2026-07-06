import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  server: {
    host: "0.0.0.0",
    proxy: {
      "/api": {
        target: "http://localhost:8080",
        changeOrigin: true
      },
      "/uploads": {
        target: "http://localhost:8080",
        changeOrigin: true
      }
    }
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes("node_modules")) {
            return undefined;
          }
          if (id.includes("element-plus")) {
            return "element";
          }
          if (id.includes("vue") || id.includes("vue-router") || id.includes("pinia")) {
            return "vue";
          }
          if (id.includes("axios")) {
            return "axios";
          }
          return "vendor";
        }
      }
    }
  }
});
