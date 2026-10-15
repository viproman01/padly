// Real Windows DPAPI plus independent browser crypto, across receiver process restarts.
// PADLY_RECEIVER_EXE=<published exe> node --test windows/tests/persistence.mjs
// Or PADLY_RECEIVER_DLL=<built dll> DOTNET_EXE=<dotnet> node --test ...
import assert from 'node:assert/strict';
import test from 'node:test';
import { spawn } from 'node:child_process';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { createServer } from 'node:net';
import { createInterface } from 'node:readline';
import WebSocket from '../../web/node_modules/ws/index.js';
import { PadlyClient } from '../../web/src/client.js';
import { MemoryStorage } from '../../web/tests/helpers/mock-mac.mjs';

const executable = process.env.PADLY_RECEIVER_EXE;
const dll = process.env.PADLY_RECEIVER_DLL;
assert.ok(executable || dll, 'Set PADLY_RECEIVER_EXE or PADLY_RECEIVER_DLL.');

async function waitFor(predicate, description, timeoutMs = 10000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  assert.fail(`Timed out waiting for ${description}`);
}

async function freePort() {
  const socket = createServer();
  await new Promise((resolve, reject) => { socket.once('error', reject); socket.listen(0, '127.0.0.1', resolve); });
  const port = socket.address().port;
  await new Promise(resolve => socket.close(resolve));
  return port;
}

async function runReceiver(directory, expectReady = true) {
  const httpPort = await freePort();
  let wsPort = await freePort();
  while (wsPort === httpPort) wsPort = await freePort();
  const args = ['--headless', '--diagnostic', '--ws-port', String(wsPort), '--http-port', String(httpPort), '--data-dir', directory];
  const command = executable ? resolve(executable) : process.env.DOTNET_EXE || 'dotnet';
  const child = spawn(command, executable ? args : [resolve(dll), ...args], { stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true,
    env: { ...process.env, DOTNET_NOLOGO: '1', DOTNET_SKIP_FIRST_TIME_EXPERIENCE: '1' } });
  const events = [], logs = [], clients = [];
  let exit, processError;
  child.on('error', error => { processError = error; });
  child.on('exit', (code, signal) => { exit = { code, signal }; });
  child.stderr.setEncoding('utf8'); child.stderr.on('data', data => logs.push(data));
  const lines = createInterface({ input: child.stdout });
  lines.on('line', line => { try { events.push(JSON.parse(line)); } catch { logs.push(line); } });
  const f = { child, events, logs, baseURL: `http://127.0.0.1:${httpPort}`,
    get ready() { return events.findLast(event => event.type === 'ready'); },
    get exit() { return exit; },
    command(command) { child.stdin.write(JSON.stringify({ command }) + '\n'); },
    client(storage = new MemoryStorage()) {
      const states = [];
      const client = new PadlyClient({ baseURL: this.baseURL, storage, WebSocketImpl: WebSocket, fetchImpl: fetch, onState: state => states.push(state) });
      clients.push(client); return { client, states, storage };
    },
    async stop() {
      if (exit === undefined && !processError) {
        this.command('stop');
        await waitFor(() => exit !== undefined, 'clean process shutdown');
        assert.equal(exit.code, 0, logs.join('\n'));
      }
      for (const client of clients) client.close();
      lines.close();
    },
    kill() { for (const client of clients) client.close(); if (exit === undefined) child.kill(); lines.close(); },
  };
  try {
    await waitFor(() => {
      if (processError) throw processError;
      if (expectReady && exit) assert.fail(`Receiver exited: ${JSON.stringify(exit)}\n${logs.join('\n')}`);
      return expectReady ? !!f.ready : exit !== undefined;
    }, expectReady ? 'persistent receiver startup' : 'corrupt-store rejection', 15000);
    return f;
  } catch (error) { f.kill(); throw error; }
}

test('DPAPI credentials resume after receiver process restart and revocation survives another restart', { timeout: 60000 }, async t => {
  const directory = await mkdtemp(join(tmpdir(), 'padly-dpapi-'));
  const processes = [];
  t.after(async () => { processes.forEach(f => f.kill()); await rm(directory, { recursive: true, force: true }); });
  const first = await runReceiver(directory); processes.push(first);
  const session = first.client();
  await session.client.connect();
  await waitFor(() => session.client.status === 'pin', 'initial PIN challenge');
  session.client.submitPin(first.ready.pin);
  await waitFor(() => session.client.status === 'ready', 'initial authenticated session');
  const serverId = first.ready.serverId;
  const storedFile = join(directory, 'pairings.dpapi');
  const encrypted = await readFile(storedFile);
  assert.ok(encrypted.length > 100, 'real protected store exists');
  assert.ok(!encrypted.includes(Buffer.from(session.client.pair.ltk)), 'LTK is not stored in cleartext');
  assert.ok(!encrypted.includes(Buffer.from(session.client.pair.deviceId)), 'device identity is inside the protected document');
  session.client.send('cursorClick', { btn: 0, down: true, ts: Date.now() });
  await waitFor(() => first.events.some(event => event.kind === 'button' && event.down), 'held input before process shutdown');
  await first.stop();
  assert.ok(first.events.some(event => event.kind === 'release'), 'shutdown released input');

  const second = await runReceiver(directory); processes.push(second);
  assert.equal(second.ready.serverId, serverId, 'server identity persisted');
  const resumed = second.client(session.storage);
  await resumed.client.connect();
  await waitFor(() => resumed.client.status === 'ready', 'LTK resume after process restart');
  assert.ok(!resumed.states.some(state => state.status === 'pin'), 'restart does not require a new PIN');
  resumed.client.send('text', { s: 'После перезапуска 👋', ts: Date.now() });
  await waitFor(() => second.events.some(event => event.kind === 'text' && event.text === 'После перезапуска 👋'), 'authenticated input after restart');
  second.command('forget');
  await waitFor(() => ['disconnected', 'error'].includes(resumed.client.status), 'active credential revoked');
  await second.stop();

  const third = await runReceiver(directory); processes.push(third);
  assert.equal(third.ready.serverId, serverId, 'revocation preserves server identity');
  const forgotten = third.client(session.storage);
  await forgotten.client.connect();
  await waitFor(() => forgotten.client.status === 'pin', 'revoked credentials cannot resume after restart');
  assert.ok(!forgotten.states.some(state => state.status === 'ready'));
  assert.equal(forgotten.client.send('text', { s: 'forbidden', ts: 1 }), false);
  forgotten.client.submitPin(third.ready.pin);
  await waitFor(() => forgotten.client.status === 'ready', 'fresh PIN can pair the revoked browser again');
  await third.stop();
});

test('unreadable DPAPI file prevents startup and is preserved byte-for-byte', { timeout: 30000 }, async t => {
  const directory = await mkdtemp(join(tmpdir(), 'padly-corrupt-'));
  const processes = [];
  t.after(async () => { processes.forEach(f => f.kill()); await rm(directory, { recursive: true, force: true }); });
  const first = await runReceiver(directory); processes.push(first); await first.stop();
  const path = join(directory, 'pairings.dpapi');
  const corrupted = Buffer.from('invalid-DPAPI-test-file');
  await writeFile(path, corrupted);
  const rejected = await runReceiver(directory, false); processes.push(rejected);
  assert.equal(rejected.exit.code, 1, 'startup must fail rather than replace credentials');
  assert.equal(rejected.ready, undefined);
  assert.deepEqual(await readFile(path), corrupted);
  assert.ok(rejected.logs.join('\n').includes('хранилище'), 'startup explains the protected-store error: ' + rejected.logs.join('\n'));
});
