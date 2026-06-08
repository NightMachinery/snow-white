// Minimal ambient declaration for transit-js (no official types).
// We only use a small surface; `any` is acceptable at this boundary.
declare module 'transit-js' {
	const transit: any;
	export default transit;
}
