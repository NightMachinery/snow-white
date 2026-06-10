// The room is a live, client-only WebSocket experience. Disabling SSR avoids
// running connection logic on the server and prevents the shared `conn`
// singleton from leaking state between requests during server rendering.
export const ssr = false;
export const prerender = false;
