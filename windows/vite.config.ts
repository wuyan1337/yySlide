import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { fileURLToPath, URL } from "node:url";

const projectRoot = fileURLToPath(new URL(".", import.meta.url));

export default defineConfig({
  root: projectRoot,
  plugins: [react()],
  base: "./",
  resolve: {
    alias: {
      "monaco-editor/min/vs/editor/editor.main.css": fileURLToPath(
        new URL("./node_modules/monaco-editor/min/vs/editor/editor.main.css", import.meta.url)
      )
    }
  },
  server: {
    host: "127.0.0.1",
    port: 5173,
    strictPort: true
  },
  build: {
    outDir: "dist",
    emptyOutDir: true
  }
});
