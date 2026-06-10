import adapter from '@sveltejs/adapter-static';

/** @type {import('@sveltejs/kit').Config} */
const config = {
	compilerOptions: {
		// Force runes mode for the project, except for libraries. Can be removed in svelte 6.
		runes: ({ filename }) => (filename.split(/[/\\]/).includes('node_modules') ? undefined : true)
	},
	kit: {
		// Static output lets Caddy serve the built app directly; the Clojure
		// backend still handles `/api`, `/ws`, and `/health`.
		adapter: adapter({ fallback: 'index.html' })
	}
};

export default config;
