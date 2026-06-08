import { sveltekit } from '@sveltejs/kit/vite';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vite';

// The Clojure backend runs on :3000. In dev we proxy the HTTP API and the
// WebSocket through Vite so the browser only ever talks to the Vite origin
// (no CORS, and the WS upgrade is forwarded transparently with `ws: true`).
const BACKEND = process.env.SNOW_BACKEND ?? 'http://localhost:3000';

export default defineConfig({
	plugins: [tailwindcss(), sveltekit()],
	server: {
		proxy: {
			'/api': { target: BACKEND, changeOrigin: true },
			'/ws': { target: BACKEND, ws: true, changeOrigin: true }
		}
	}
});
