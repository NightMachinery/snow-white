#!/usr/bin/env node
// Reliable Chrome screenshotter + DOM prober over the Chrome DevTools Protocol,
// talking straight to a headful Chrome on :9222 using Node's BUILT-IN WebSocket
// (Node >= 22). No dependencies, no MCP server in the loop.
//
// Why this exists: the chrome-devtools MCP server in this repo (.mcp.json) is
// flaky — individual tools (take_screenshot/take_snapshot/evaluate_script)
// intermittently vanish from the toolset mid-session and need a /mcp reconnect.
// This script hits the same Chrome directly, so it keeps working when the MCP
// doesn't. See ./README.md for the full story and the multi-player workflow.
//
// Usage:
//   node shot.mjs --out=PATH [--room=NAME] [--auth=ID] [--name=NAME] \
//                 [--theme=light|dark] [--vp=desktop|mobile] [--probe]
//
// Examples:
//   # Home page, dark, mobile:
//   node shot.mjs --vp=mobile --theme=dark --out=/tmp/home.png
//   # A room as a specific seated player (identity set in localStorage first):
//   node shot.mjs --room=darkroom --auth=v2 --name=Gretel --theme=dark \
//                 --vp=desktop --probe --out=/tmp/qround.png
//
// --probe prints JSON: { dirAuto, htmlDir, hscroll, iw, sw, body } — handy for
// asserting "no horizontal scroll" and reading on-screen text without pixels.
//
// Identity note: the room is client-only and reads identity from localStorage
// (snow:auth-id / snow:name / snow:theme). This script loads the origin first to
// set those keys, THEN navigates to the room — so one Chrome tab can impersonate
// any player in turn. Drive the *other* players from the backend REPL (see
// README); you only need a browser for the perspective you're screenshotting.

const args = Object.fromEntries(
  process.argv.slice(2).map((a) => {
    const [k, ...v] = a.replace(/^--/, '').split('=');
    return [k, v.join('=') || true];
  })
);
const { room = '', auth = '', name = '', theme = 'light', vp = 'desktop', out, probe } = args;
const ORIGIN = process.env.SNOW_ORIGIN || 'http://localhost:38932';
const CDP = process.env.CHROME_CDP || 'http://127.0.0.1:9222';

// Pick an existing page target, or create one.
const targets = await (await fetch(`${CDP}/json`)).json();
const target = targets.find((t) => t.type === 'page') || (await (await fetch(`${CDP}/json/new`)).json());

const ws = new WebSocket(target.webSocketDebuggerUrl);
let id = 0;
const pending = new Map();
const send = (method, params = {}) =>
  new Promise((res) => {
    const i = ++id;
    pending.set(i, res);
    ws.send(JSON.stringify({ id: i, method, params }));
  });
await new Promise((r) => (ws.onopen = r));
ws.onmessage = (m) => {
  const d = JSON.parse(m.data);
  if (d.id && pending.has(d.id)) {
    pending.get(d.id)(d.result);
    pending.delete(d.id);
  }
};

await send('Page.enable');
await send('Runtime.enable');

// Viewport. A headful Chrome window may be small, so DON'T rely on resizing the
// OS window — override metrics via CDP instead (this is the equivalent of the
// MCP `emulate` tool, and unlike `resize_page` it is not clamped by the window).
const dims =
  vp === 'mobile'
    ? { width: 390, height: 844, deviceScaleFactor: 2, mobile: true }
    : { width: 1280, height: 850, deviceScaleFactor: 1, mobile: false };
await send('Emulation.setDeviceMetricsOverride', dims);

// Load the origin first so we can write identity/theme into localStorage...
await send('Page.navigate', { url: `${ORIGIN}/` });
await new Promise((r) => setTimeout(r, 800));
const setLS = `(${(a, n, t) => {
  try {
    if (a) localStorage.setItem('snow:auth-id', a);
    if (n) localStorage.setItem('snow:name', n);
    localStorage.setItem('snow:theme', t);
    return 'ok';
  } catch (e) {
    return '' + e;
  }
}})(${JSON.stringify(auth)}, ${JSON.stringify(name)}, ${JSON.stringify(theme)})`;
await send('Runtime.evaluate', { expression: setLS });

// ...then navigate to the actual target (room or home).
const url = room ? `${ORIGIN}/room/${encodeURIComponent(room)}` : `${ORIGIN}/`;
await send('Page.navigate', { url });
await new Promise((r) => setTimeout(r, 2200)); // let the WS connect + render

if (probe) {
  const expr = `(${() => {
    const els = [...document.querySelectorAll('[dir="auto"]')];
    return JSON.stringify({
      dirAuto: els.length,
      htmlDir: getComputedStyle(document.documentElement).direction,
      hscroll: document.documentElement.scrollWidth > window.innerWidth,
      iw: window.innerWidth,
      sw: document.documentElement.scrollWidth,
      body: document.body.innerText.slice(0, 400)
    });
  }})()`;
  const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true });
  console.log(r.result?.value || JSON.stringify(r));
}

if (out) {
  const cap = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
  if (cap.data) {
    const fs = await import('fs');
    fs.writeFileSync(out, Buffer.from(cap.data, 'base64'));
    console.log('SAVED', out);
  } else {
    console.log('FAIL', JSON.stringify(cap).slice(0, 150));
  }
}

ws.close();
