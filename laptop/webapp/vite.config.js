import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// base: "./" -> relative asset URLs so the built app works served from the
// Python static server at http://<host>:8080/ (offline, no CDN).
export default defineConfig({
  base: "./",
  plugins: [react()],
  build: { outDir: "dist", emptyOutDir: true, chunkSizeWarningLimit: 1200 },
});
