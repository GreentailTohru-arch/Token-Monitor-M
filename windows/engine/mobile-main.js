'use strict';
// Original Token Monitor runs unchanged under an isolated companion profile.
// This wrapper maps only channels explicitly exported by its own preload.
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const crypto = require('node:crypto');
const { app, BrowserWindow, ipcMain } = require('electron');
const profile = process.env.TOKEN_MONITOR_MOBILE_PROFILE || path.join(process.env.LOCALAPPDATA, 'TokenMonitorMobile', 'profile');
fs.mkdirSync(profile, { recursive: true });
app.setPath('userData', profile);
// Give the clone its own shell/autostart identity; original profile and registry
// startup entry are outside this companion's ownership.
const setName = app.setName.bind(app);
app.setName = () => setName('Token Monitor Mobile Companion');
const setAppId = app.setAppUserModelId.bind(app);
app.setAppUserModelId = () => setAppId('com.javis.tokenmonitor.mobilecompanion');
const getLogin = app.getLoginItemSettings.bind(app);
const setLogin = app.setLoginItemSettings.bind(app);
app.getLoginItemSettings = options => getLogin({ ...options, name: 'TokenMonitorMobileCompanion' });
app.setLoginItemSettings = options => setLogin({ ...options, name: 'TokenMonitorMobileCompanion' });
process.env.TOKEN_MONITOR_SHARED_DIR = profile;
process.env.TOKEN_MONITOR_USAGE_WORKER = '0'; // copied runtime uses exact in-process collector
process.env.TOKEN_MONITOR_MOBILE_ENGINE = '1';
const keyPath = process.env.TOKEN_MONITOR_MOBILE_KEY_FILE || path.join(process.env.LOCALAPPDATA, 'TokenMonitorMobile', 'pairing.key');
const pairingKey = fs.readFileSync(keyPath, 'utf8').trim();
if (pairingKey.length < 16) throw new Error('Invalid mobile pairing key');
const handlers = new Map();
const originalHandle = ipcMain.handle.bind(ipcMain);
const originalRemove = ipcMain.removeHandler.bind(ipcMain);
ipcMain.handle = (channel, handler) => { handlers.set(channel, handler); return originalHandle(channel, handler); };
ipcMain.removeHandler = channel => { handlers.delete(channel); return originalRemove(channel); };
const preload = fs.readFileSync(path.join(__dirname, 'src/electron/preload.js'), 'utf8');
const allowed = new Set([...preload.matchAll(/ipcRenderer\.(?:invoke|send)\(\s*['"]([^'"]+)['"]/g)].map(x => x[1]));
const sendChannels = new Set([...preload.matchAll(/ipcRenderer\.send\(\s*['"]([^'"]+)['"]/g)].map(x => x[1]));
const eventChannels = new Set([...preload.matchAll(/ipcRenderer\.(?:on|once)\(\s*['"]([^'"]+)['"]/g)].map(x => x[1]));
// A companion updates by rebuilding from the installed version, never running
// the original installer which could replace the user's separate installation.
const desktopOnly = new Set(['appUpdate:download', 'appUpdate:install']);
let cursor = 0;
let events = [];
let lastMainContents;
let rpcWindow;

function sanitize(value, depth = 0) {
  if (depth > 35) return null;
  if (value == null || typeof value !== 'object') return value;
  if (Array.isArray(value)) return value.map(x => sanitize(x, depth + 1));
  if (Buffer.isBuffer(value) || ArrayBuffer.isView(value)) return Array.from(value);
  const result = {};
  for (const [key, item] of Object.entries(value)) {
    // Provider secrets never cross the bridge, including unexpected future
    // fields. Hub connection secrets are also blank on the mobile interface.
    if (/^(access_?token|refresh_?token|id_?token|api_?key|password|cookie|cookieHeader|authorization|secret|hubHostSecret|clientSecret)$/i.test(key)) {
      result[key] = typeof item === 'string' ? '' : null;
    } else result[key] = sanitize(item, depth + 1);
  }
  return result;
}

app.on('web-contents-created', (_event, contents) => {
  const originalSend = contents.send.bind(contents);
  contents.send = (channel, ...args) => {
    if (eventChannels.has(channel)) {
      const event = { id: ++cursor, channel, args: sanitize(args) };
      // Retain the latest large stats/settings state once, not sixty copies.
      if (channel === 'stats:push' || channel === 'settings:push') events = events.filter(x => x.channel !== channel);
      events.push(event);
      if (events.length > 256) events.shift();
    }
    return originalSend(channel, ...args);
  };
});

function sender() {
  const candidates = BrowserWindow.getAllWindows().filter(w => !w.isDestroyed());
  let main = candidates.find(w => /(?:\/|\\)renderer(?:\/|\\)index\.html/.test(w.webContents.getURL()))
    || candidates.find(w => /index\.html/.test(w.webContents.getURL())) || candidates[0];
  // Tray mode can destroy the desktop window while collection keeps running.
  // IPC handlers still need a real sender for authenticated mobile requests.
  if (!main) {
    rpcWindow = new BrowserWindow({ show: false, webPreferences: { sandbox: true } });
    main = rpcWindow;
  }
  lastMainContents = main.webContents;
  return { sender: main.webContents, senderFrame: main.webContents.mainFrame,
           reply: (channel, ...args) => main.webContents.send(channel, ...args) };
}

async function rpc(channel, args) {
  if (channel === 'mobile:status') return { version: require('./package.json').version, ready: handlers.has('stats:get'),
    channels: [...allowed].filter(x => !desktopOnly.has(x)), desktopOnly: [...desktopOnly] };
  if (channel === 'mobile:events') {
    const after = Number(args[0]) || 0;
    return { cursor, events: events.filter(x => x.id > after) };
  }
  if (!allowed.has(channel)) throw new Error('channel_not_allowed');
  if (desktopOnly.has(channel)) throw new Error('请在电脑原版 Token Monitor 中更新，再重新准备手机伴随服务');
  const event = sender();
  if (sendChannels.has(channel)) { ipcMain.emit(channel, event, ...args); return null; }
  const handler = handlers.get(channel);
  if (!handler) throw new Error('engine_starting_or_channel_unavailable');
  return sanitize(await handler(event, ...args));
}

function reply(res, status, data) {
  const body = JSON.stringify(data);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store',
    'Content-Length': Buffer.byteLength(body), 'X-Content-Type-Options': 'nosniff' });
  res.end(body);
}

const server = http.createServer(async (req, res) => {
  if (req.method !== 'POST' || req.url !== '/api/rpc') return reply(res, 404, { ok: false, error: 'not_found' });
  const supplied = Buffer.from(req.headers.authorization || '');
  const expected = Buffer.from('Bearer ' + pairingKey);
  if (supplied.length !== expected.length || !crypto.timingSafeEqual(supplied, expected)) return reply(res, 401, { ok: false, error: 'unauthorized' });
  let size = 0;
  const chunks = [];
  try {
    for await (const chunk of req) {
      size += chunk.length;
      if (size > 2 * 1024 * 1024) { reply(res, 413, { ok: false, error: 'request_too_large' }); req.destroy(); return; }
      chunks.push(chunk);
    }
    const data = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    if (typeof data.channel !== 'string' || !Array.isArray(data.args || [])) throw new Error('invalid_request');
    const result = await rpc(data.channel, data.args || []);
    reply(res, 200, { ok: true, result: result === undefined ? null : result });
  } catch (error) {
    reply(res, 200, { ok: false, error: String(error?.message || 'rpc_failed').slice(0, 500) });
  }
});
server.requestTimeout = 30000;
server.headersTimeout = 10000;
server.on('error', error => { console.error('[mobile-engine]', error.code); app.quit(); });
server.listen(Number(process.env.TOKEN_MONITOR_MOBILE_ENGINE_PORT || 47822), '127.0.0.1', () => console.log('[mobile-engine] RPC ready on loopback'));
app.on('will-quit', () => server.close());
require('./src/electron/main.js');
