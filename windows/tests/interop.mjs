// Exercise the real Windows receiver with the independent JavaScript browser
// client. Diagnostic mode records authenticated input and never calls SendInput.
// Usage: PADLY_RECEIVER_EXE=<published exe> node --test windows/tests/interop.mjs
// Or: PADLY_RECEIVER_DLL=<built dll> DOTNET_EXE=<dotnet> node --test ...
import assert from 'node:assert/strict';
import test from 'node:test';
import { spawn } from 'node:child_process';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { createServer } from 'node:net';
import { createInterface } from 'node:readline';
import WebSocket from '../../web/node_modules/ws/index.js';
import { PadlyClient } from '../../web/src/client.js';
import { pack } from '../../web/src/protocol.js';
import { MemoryStorage } from '../../web/tests/helpers/mock-mac.mjs';

const executable = process.env.PADLY_RECEIVER_EXE;
const dll = process.env.PADLY_RECEIVER_DLL;
assert.ok(executable || dll, 'Set PADLY_RECEIVER_EXE or PADLY_RECEIVER_DLL to the real Windows receiver.');

async function waitFor(predicate, description, timeoutMs = 6000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  assert.fail(`Timed out waiting for ${description}`);
}

async function freePort() {
  const server = createServer();
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}

async function fixture(t) {
  const directory = await mkdtemp(join(tmpdir(), 'padly-interop-'));
  const httpPort = await freePort();
  let wsPort = await freePort();
  while (wsPort === httpPort) wsPort = await freePort();
  const args = ['--headless', '--ephemeral', '--diagnostic', '--ws-port', String(wsPort),
    '--http-port', String(httpPort), '--data-dir', directory];
  const command = executable ? resolve(executable) : process.env.DOTNET_EXE || 'dotnet';
  const child = spawn(command, executable ? args : [resolve(dll), ...args], {
    stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true,
    env: { ...process.env, DOTNET_NOLOGO: '1', DOTNET_SKIP_FIRST_TIME_EXPERIENCE: '1' },
  });
  const events = [];
  const clients = [];
  const logs = [];
  let processError;
  let exit;
  child.on('error', error => { processError = error; });
  child.on('exit', (code, signal) => { exit = { code, signal }; });
  child.stderr.setEncoding('utf8');
  child.stderr.on('data', chunk => logs.push(chunk));
  const lines = createInterface({ input: child.stdout });
  lines.on('line', line => {
    try { events.push(JSON.parse(line)); }
    catch { logs.push(line); }
  });
  const f = {
    child, events, logs, clients, baseURL: `http://127.0.0.1:${httpPort}`, wsPort,
    get input() { return events.filter(event => event.type === 'input'); },
    get pin() { return events.findLast(event => event.type === 'ready')?.pin; },
    command(command) { child.stdin.write(`${JSON.stringify({ command })}\n`); },
    client(storage = new MemoryStorage()) {
      const states = [];
      const client = new PadlyClient({ baseURL: this.baseURL, storage, WebSocketImpl: WebSocket,
        fetchImpl: fetch, onState: state => states.push(state) });
      const result = { client, states, storage };
      clients.push(client);
      return result;
    },
    assertRunning() {
      assert.equal(processError, undefined, `receiver process error: ${processError}`);
      assert.equal(exit, undefined, `receiver exited: ${JSON.stringify(exit)}\n${logs.join('\n')}`);
    },
  };
  t.after(async () => {
    for (const client of clients) client.close();
    if (exit === undefined && !processError) {
      try { f.command('stop'); } catch { /* Already exiting. */ }
      await waitFor(() => exit !== undefined, 'receiver shutdown', 3000).catch(() => child.kill());
    }
    lines.close();
    assert.equal(dirname(resolve(directory)), resolve(tmpdir()), 'cleanup must stay inside the system temp directory');
    assert.ok(basename(directory).startsWith('padly-interop-'), 'cleanup must target the generated test directory');
    await rm(directory, { recursive: true, force: true });
  });
  await waitFor(() => {
    if (processError) throw processError;
    if (exit) assert.fail(`Receiver exited during startup: ${JSON.stringify(exit)}\n${logs.join('\n')}`);
    return events.some(event => event.type === 'ready');
  }, 'receiver startup', 15000);
  const ready = events.find(event => event.type === 'ready');
  assert.equal(ready.httpPort, httpPort);
  assert.equal(ready.wsPort, wsPort);
  assert.match(ready.pin, /^[0-9]{6}$/);
  assert.equal(typeof ready.serverId, 'string');
  return f;
}

async function begin(f, session = f.client()) {
  await session.client.connect();
  await waitFor(() => session.client.status === 'pin', 'PIN entry');
  return session;
}

async function pair(f, session) {
  const s = await begin(f, session);
  assert.equal(s.client.submitPin(f.pin), true);
  await waitFor(() => s.client.status === 'ready', 'authenticated ready state');
  return s;
}

function onlyKinds(events, ...kinds) { return events.filter(event => kinds.includes(event.kind)); }

test('real receiver serves browser assets and config without PIN or keys', async t => {
  const f = await fixture(t);
  const response = await fetch(`${f.baseURL}/config.json`);
  assert.equal(response.status, 200);
  assert.match(response.headers.get('content-type'), /application\/json/i);
  const configText = await response.text();
  const config = JSON.parse(configText);
  assert.equal(config.wsPort, f.wsPort);
  assert.equal(config.platform, 'windows');
  assert.equal(typeof config.name, 'string');
  assert.match(response.headers.get('cache-control'), /no-store/i);
  assert.ok(!configText.includes(f.pin));
  for (const key of Object.keys(config)) assert.ok(!/pin|ltk|secret|private|pair/i.test(key), `private config key ${key}`);
  for (const [path, contentType, expected] of [
    ['/', /text\/html/i, 'id="touchpad"'],
    ['/app.js', /javascript/i, ''],
    ['/styles.css', /text\/css/i, ''],
  ]) {
    const asset = await fetch(`${f.baseURL}${path}`);
    assert.equal(asset.status, 200, path);
    assert.match(asset.headers.get('content-type'), contentType);
    const body = await asset.text();
    assert.ok(body.length > 100, `${path} is a real asset`);
    if (expected) assert.ok(body.includes(expected));
    assert.ok(asset.headers.get('content-security-policy'), `${path} has a CSP`);
  }
  assert.equal((await fetch(`${f.baseURL}/pairings.dpapi`)).status, 404);
  assert.equal((await fetch(`${f.baseURL}/not-a-browser-asset`)).status, 404);
  const head = await fetch(`${f.baseURL}/`, { method: 'HEAD' });
  assert.equal(head.status, 200);
  assert.ok(Number(head.headers.get('content-length')) > 100);
  assert.equal(await head.text(), '');
  assert.equal((await fetch(`${f.baseURL}/config.json`, { method: 'POST' })).status, 405);
  f.assertRunning();
});

test('unrelated browser Origin cannot open the receiver WebSocket', async t => {
  const f = await fixture(t);
  const socket = new WebSocket(`ws://127.0.0.1:${f.wsPort}/`, { origin: 'https://unrelated.example' });
  let status;
  let opened = false;
  socket.on('open', () => { opened = true; });
  socket.on('error', () => {});
  socket.on('unexpected-response', (_request, response) => { status = response.statusCode; response.resume(); });
  try { await waitFor(() => status !== undefined || opened, 'Origin rejection'); assert.equal(opened, false); assert.equal(status, 403); }
  finally { socket.terminate(); }
  f.assertRunning();
});

test('real receiver rejects unauthenticated input before PIN', async t => {
  const f = await fixture(t);
  const s = await begin(f);
  const before = onlyKinds(f.input, 'move', 'button', 'scroll', 'text', 'key').length;
  assert.equal(s.client.send('text', { s: 'before PIN', ts: Date.now() }), false);
  // Bypass the UI guard to prove that authorization is enforced by C# too.
  for (let i = 0; i < 10; i++)
    assert.equal(s.client.sendSealed('text', { s: 'unauthenticated', ts: Date.now() }), true);
  await waitFor(() => ['error', 'disconnected'].includes(s.client.status), 'unauthenticated input rejected');
  assert.equal(onlyKinds(f.input, 'move', 'button', 'scroll', 'text', 'key').length, before);
  f.assertRunning();
});

test('real C# crypto pairs with browser and routes authenticated cursor, buttons, scrolling, text and keys', async t => {
  const f = await fixture(t);
  const s = await pair(f);
  const before = f.input.length;
  const input = [
    ['cursorMove', { dx: 10.5, dy: -2.25 }],
    ['cursorClick', { btn: 0, down: true }],
    ['cursorClick', { btn: 0, down: false }],
    ['cursorClick', { btn: 1, down: true }],
    ['cursorClick', { btn: 1, down: false }],
    ['scroll', { dx: -3, dy: 12, momentum: false }],
    ['text', { s: 'Привет 👋' }],
    ['key', { code: 42, mods: 0, down: true }],
    ['key', { code: 42, mods: 0, down: false }],
  ];
  for (const [type, fields] of input) assert.equal(s.client.send(type, { ...fields, ts: Date.now() }), true);
  await waitFor(() => f.input.length >= before + input.length, 'all diagnostic input');
  const actual = f.input.slice(before).filter(event => event.kind !== 'release');
  assert.deepEqual(actual.map(({ kind, dx, dy, button, down, text, code, mods }) =>
    Object.fromEntries(Object.entries({ kind, dx, dy, button, down, text, code, mods }).filter(([, value]) => value !== undefined))), [
    { kind: 'move', dx: 10.5, dy: -2.25 },
    { kind: 'button', button: 0, down: true }, { kind: 'button', button: 0, down: false },
    { kind: 'button', button: 1, down: true }, { kind: 'button', button: 1, down: false },
    { kind: 'scroll', dx: -3, dy: 12 },
    { kind: 'text', text: 'Привет 👋' },
    { kind: 'key', code: 42, mods: 0, down: true }, { kind: 'key', code: 42, mods: 0, down: false },
  ]);
  assert.ok(s.storage.length > 0);
  f.assertRunning();
});

test('an incorrect PIN can be retried and new PIN invalidates the prior code', async t => {
  const f = await fixture(t);
  const prior = f.pin;
  const readyCount = f.events.filter(event => event.type === 'ready').length;
  f.command('new-pin');
  await waitFor(() => f.events.filter(event => event.type === 'ready').length > readyCount, 'new PIN');
  assert.match(f.pin, /^[0-9]{6}$/);
  // Random regeneration may choose the same six digits; a deliberately different
  // code always exercises retry without making this test probabilistically fail.
  const wrong = prior === f.pin ? (f.pin === '000000' ? '000001' : '000000') : prior;
  const s = await begin(f);
  const before = s.states.length;
  assert.equal(s.client.submitPin(wrong), true);
  await waitFor(() => s.states.length > before && s.client.status === 'pin', 'wrong PIN response');
  assert.ok(s.states.at(-1).attemptsLeft > 0);
  assert.equal(s.client.send('text', { s: 'not authorized', ts: 1 }), false);
  s.client.submitPin(f.pin);
  await waitFor(() => s.client.status === 'ready', 'PIN retry success');
  f.assertRunning();
});

test('five incorrect PINs lock real receiver pairing', async t => {
  const f = await fixture(t);
  const s = await begin(f);
  const wrong = f.pin === '000000' ? '000001' : '000000';
  for (let i = 0; i < 5; i++) {
    const before = s.states.length;
    assert.equal(s.client.submitPin(wrong), true);
    await waitFor(() => s.states.length > before, `failed PIN ${i + 1}`);
  }
  await waitFor(() => ['error', 'disconnected'].includes(s.client.status), 'PIN lockout');
  assert.ok(!s.states.some(state => state.status === 'ready'));
  assert.equal(s.client.send('text', { s: 'locked', ts: 1 }), false);
  assert.deepEqual(onlyKinds(f.input, 'move', 'button', 'scroll', 'text', 'key'), []);
  f.assertRunning();
});

test('stored LTK resumes without PIN and a new controller releases and replaces the old one', async t => {
  const f = await fixture(t);
  const initial = await pair(f);
  initial.client.send('cursorClick', { btn: 0, down: true, ts: Date.now() });
  await waitFor(() => f.input.some(event => event.kind === 'button' && event.down), 'old controller holds a button');
  const resumed = f.client(initial.storage);
  await resumed.client.connect();
  await waitFor(() => resumed.client.status === 'ready', 'stored-key resume');
  assert.ok(!resumed.states.some(state => state.status === 'pin'));
  const before = f.input.length;
  assert.equal(resumed.client.send('text', { s: 'resumed', ts: Date.now() }), true);
  await waitFor(() => f.input.slice(before).some(event => event.kind === 'text' && event.text === 'resumed'), 'resumed text');
  await waitFor(() => ['error', 'disconnected'].includes(initial.client.status), 'previous controller closed');
  const handover = f.input.slice(before);
  const released = handover.findIndex(event => event.kind === 'release');
  const typed = handover.findIndex(event => event.kind === 'text' && event.text === 'resumed');
  assert.ok(released >= 0 && released < typed, 'previous held input must release before new controller input');
  f.assertRunning();
});

test('receiver disconnect and forget release input and revoke active sessions and stored pairing', async t => {
  const f = await fixture(t);
  const initial = await pair(f);
  initial.client.send('cursorClick', { btn: 0, down: true, ts: Date.now() });
  await waitFor(() => f.input.some(event => event.kind === 'button' && event.down), 'held button');
  let before = f.input.length;
  f.command('disconnect');
  await waitFor(() => ['error', 'disconnected'].includes(initial.client.status), 'local disconnect');
  await waitFor(() => f.input.slice(before).some(event => event.kind === 'release'), 'disconnect release');
  const resumed = f.client(initial.storage);
  await resumed.client.connect();
  await waitFor(() => resumed.client.status === 'ready', 'resume before forget');
  before = f.input.length;
  f.command('forget');
  await waitFor(() => ['error', 'disconnected'].includes(resumed.client.status), 'revoked active session');
  await waitFor(() => f.input.slice(before).some(event => event.kind === 'release'), 'revoke release');
  assert.equal(resumed.client.send('text', { s: 'revoked', ts: 1 }), false);
  const readyCount = f.events.filter(event => event.type === 'ready').length;
  f.command('new-pin');
  await waitFor(() => f.events.filter(event => event.type === 'ready').length > readyCount, 'fresh pairing after forget');
  const forgotten = f.client(initial.storage);
  await forgotten.client.connect();
  await waitFor(() => forgotten.client.status === 'pin', 'forgotten LTK requires PIN');
  assert.ok(!forgotten.states.some(state => state.status === 'ready'));
  assert.equal(forgotten.client.send('text', { s: 'stored key revoked', ts: 1 }), false);
  f.assertRunning();
});

async function rejectedFrame(f, frame) {
  const socket = new WebSocket(`ws://127.0.0.1:${f.wsPort}/`);
  let closed = false;
  let opened = false;
  socket.on('error', () => {});
  socket.on('close', () => { closed = true; });
  socket.on('open', () => { opened = true; socket.send(frame); });
  try { await waitFor(() => closed, 'invalid frame closed'); assert.equal(opened, true); }
  finally { socket.terminate(); }
}

test('malformed MessagePack and frames over 64 KiB close their socket without crashing receiver', async t => {
  const f = await fixture(t);
  for (const frame of [Buffer.from([0xc1]), Buffer.from([0x82, 0xa1, 0x74]), Buffer.alloc(65537, 0)]) {
    await rejectedFrame(f, frame);
    f.assertRunning();
  }
  const valid = await pair(f);
  assert.equal(valid.client.status, 'ready');
});

for (const attack of ['tamper', 'replay']) {
  test(`real receiver rejects encrypted ${attack} without injecting input`, async t => {
    const f = await fixture(t);
    const s = await pair(f);
    const frame = s.client.sendChannel.seal(pack('ping', { ts: Date.now() }));
    if (attack === 'replay') {
      s.client.lastPong = 0;
      s.client.socket.send(frame);
      await waitFor(() => s.client.lastPong > 0, 'valid original ping before replay');
    }
    const corrupt = Buffer.from(frame);
    if (attack === 'tamper') corrupt[corrupt.length - 1] ^= 1;
    const before = onlyKinds(f.input, 'move', 'button', 'scroll', 'text', 'key').length;
    // v2 permits dropping up to nine invalid packets before closing a session.
    for (let i = 0; i < 10; i++) s.client.socket.send(corrupt);
    await waitFor(() => ['error', 'disconnected'].includes(s.client.status), `${attack} rejection`);
    assert.equal(onlyKinds(f.input, 'move', 'button', 'scroll', 'text', 'key').length, before);
    f.assertRunning();
  });
}

test('silent authenticated session expires and releases held input after loss of heartbeat', { timeout: 25000 }, async t => {
  const f = await fixture(t);
  const s = await pair(f);
  s.client.send('cursorClick', { btn: 0, down: true, ts: Date.now() });
  await waitFor(() => f.input.some(event => event.kind === 'button' && event.down), 'held button before silence');
  clearInterval(s.client.pingTimer);
  const before = f.input.length;
  await waitFor(() => ['error', 'disconnected'].includes(s.client.status), 'receiver idle timeout', 19000);
  await waitFor(() => f.input.slice(before).some(event => event.kind === 'release'), 'idle session releases input');
  f.assertRunning();
});
