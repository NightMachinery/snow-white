// Light/dark theme, persisted, applied as a `.dark` class on <html> (which the
// Tailwind `dark:` variant keys off — see app.css). Defaults to the OS setting.

import { browser } from '$app/environment';

const KEY = 'snow:theme';

function initialDark(): boolean {
	if (!browser) return false;
	const saved = localStorage.getItem(KEY);
	if (saved) return saved === 'dark';
	return window.matchMedia('(prefers-color-scheme: dark)').matches;
}

class Theme {
	dark = $state(false);

	constructor() {
		this.dark = initialDark();
	}

	apply() {
		if (!browser) return;
		document.documentElement.classList.toggle('dark', this.dark);
		localStorage.setItem(KEY, this.dark ? 'dark' : 'light');
	}

	toggle() {
		this.dark = !this.dark;
		this.apply();
	}
}

export const theme = new Theme();
