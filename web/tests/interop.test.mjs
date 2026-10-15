import assert from 'node:assert/strict';
import test from 'node:test';
import WebSocket from 'ws';
import { PadlyClient } from '../src/client.js';
import { MemoryStorage, startMockMac } from './helpers/mock-mac.mjs';

async function waitFor(predicate, description, timeoutMs = 4000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  assert.fail(`Timed out waiting for ${description}`);
}

async function fixture(t, options = {}, storage = new MemoryStorage()) {
  const mac = await startMockMac(options);
  const states = [];
  const client = new PadlyClient({
    baseURL: `${mac.baseURL}/some/browser/path`, storage, WebSocketImpl: WebSocket,
    fetchImpl: fetch, onState: state => states.push(state),
  });
  t.after(async () => { client.close(); await mac.close(); });
  return { mac, client, states, storage };
}

async function beginPairing(f) {
  await f.client.connect();
  await waitFor(() => f.states.some(s => s.status === 'pin'), 'PIN entry');
  assert.deepEqual(f.mac.configRequests, ['/config.json']);
  assert.equal(f.mac.sessions[0].known, false);
}

async function pair(f) {
  await beginPairing(f);
  f.client.submitPin(f.mac.pin);
  await waitFor(() => f.states.some(s => s.status === 'ready'), 'successful pairing');
  assert.equal(f.mac.paired.size, 1);
}

test('pairs with an independent Node Mac and sends only authenticated input', async t => {
  const f = await fixture(t);
  assert.equal(f.client.send('text', { s: 'before connect', ts: 1 }), false);
  await beginPairing(f);
  assert.equal(f.client.send('cursorMove', { dx: 1, dy: 2, ts: 2 }), false);
  assert.deepEqual(f.mac.messages, []);
  f.client.submitPin(f.mac.pin);
  await waitFor(() => f.states.some(s => s.status === 'ready'), 'ready state');

  const input = [
    ['cursorMove', { dx: 10.5, dy: -2.25, ts: 101 }],
    ['cursorClick', { btn: 0, down: true, ts: 102 }],
    ['cursorClick', { btn: 0, down: false, ts: 103 }],
    ['scroll', { dx: -3, dy: 12, momentum: false, ts: 104 }],
    ['text', { s: 'Привет 👋', ts: 105 }],
  ];
  for (const [type, fields] of input) assert.equal(f.client.send(type, fields), true);
  await waitFor(() => f.mac.messages.length === input.length, 'captured input');
  assert.deepEqual(f.mac.messages.map(({ t: type, d }) => [type, d]), input);
  assert.deepEqual(f.mac.errors, []);
  assert.ok(f.storage.length > 0, 'credentials must be persisted');
});

test('an incorrect PIN can be retried without creating a new session', async t => {
  const f = await fixture(t);
  await beginPairing(f);
  const previousStates = f.states.length;
  f.client.submitPin('000000');
  await waitFor(() => f.mac.sessions[0].pinAttempts === 1 && f.states.length > previousStates, 'incorrect PIN response');
  assert.equal(f.mac.paired.size, 0);
  assert.equal(f.client.send('text', { s: 'still unauthenticated', ts: 1 }), false);
  f.client.submitPin(f.mac.pin);
  await waitFor(() => f.states.some(s => s.status === 'ready'), 'PIN retry success');
  assert.equal(f.mac.sessions.length, 1);
  assert.equal(f.mac.sessions[0].pinAttempts, 2);
  assert.deepEqual(f.mac.errors, []);
});

test('five incorrect PINs lock pairing and prevent input', async t => {
  const f = await fixture(t);
  await beginPairing(f);
  for (let attempt = 1; attempt <= 5; attempt++) {
    const previousStates = f.states.length;
    f.client.submitPin('000000');
    await waitFor(() => f.mac.sessions[0].pinAttempts === attempt && f.states.length > previousStates, `PIN attempt ${attempt}`);
  }
  await waitFor(() => f.states.some(s => s.status === 'error' || s.status === 'disconnected'), 'lockout state');
  assert.equal(f.client.send('text', { s: 'locked', ts: 1 }), false);
  assert.equal(f.mac.paired.size, 0);
  assert.ok(!f.states.some(s => s.status === 'ready'));
  assert.deepEqual(f.mac.messages, []);
});

test('an expired pairing PIN never authenticates', async t => {
  const f = await fixture(t, { expired: true });
  await beginPairing(f);
  f.client.submitPin(f.mac.pin);
  await waitFor(() => f.states.some(s => s.status === 'error' || s.status === 'disconnected'), 'expired PIN response');
  assert.equal(f.client.send('cursorClick', { btn: 0, down: true, ts: 1 }), false);
  assert.equal(f.mac.paired.size, 0);
  assert.ok(!f.states.some(s => s.status === 'ready'));
});

test('a lost PIN response times out and cannot leave the browser pairing forever', async t => {
  const f = await fixture(t, { dropPinResponse: true });
  await beginPairing(f);
  // Clear the real entry timer before swapping timer APIs; otherwise the
  // mocked clearTimeout cannot release the original native timer handle.
  clearTimeout(f.client.timeout);
  t.mock.timers.enable({ apis: ['setTimeout'] });
  f.client.submitPin(f.mac.pin);
  t.mock.timers.tick(10001);
  t.mock.timers.reset();
  assert.equal(f.states.at(-1).status, 'error');
  assert.match(f.states.at(-1).message, /PIN/);
  assert.equal(f.client.send('text', { s: 'timed out', ts: 1 }), false);
});

test('a new browser client resumes using the stored identity and LTK without a PIN', async t => {
  const f = await fixture(t);
  await pair(f);
  const deviceId = f.mac.sessions[0].hello.deviceId;
  f.client.close();
  await waitFor(() => f.mac.sessions[0].closed, 'first connection closed');
  const states = [];
  const resumed = new PadlyClient({
    baseURL: f.mac.baseURL, storage: f.storage, WebSocketImpl: WebSocket,
    fetchImpl: fetch, onState: state => states.push(state),
  });
  t.after(() => resumed.close());
  await resumed.connect();
  await waitFor(() => states.some(s => s.status === 'ready'), 'resumed ready state');
  assert.equal(f.mac.sessions[1].known, true);
  assert.equal(f.mac.sessions[1].hello.deviceId, deviceId);
  assert.equal(f.mac.sessions[1].pinAttempts, 0);
  assert.ok(!states.some(s => s.status === 'pin'));
  assert.equal(resumed.send('text', { s: 'resumed', ts: 1 }), true);
  await waitFor(() => f.mac.messages.length === 1, 'resumed input');
  assert.deepEqual(f.mac.errors, []);
});

for (const attack of ['tamper', 'replay']) {
  test(`server ${attack} frames are rejected and the connection is closed`, async t => {
    const f = await fixture(t);
    await pair(f);
    const session = f.mac.sessions[0];
    const frame = attack === 'replay'
      ? session.send('pong', { ts: 1, serverTs: 2 })
      : session.frame('pong', { ts: 1, serverTs: 2 });
    // Replay a delivered frame; tamper with a new, unseen frame so that this
    // case tests GCM integrity independently of the sequence replay check.
    // Protocol v2 permits disconnecting immediately or after ten rejects.
    for (let i = 0; i < 10; i++) {
      const corrupted = Buffer.from(frame);
      if (attack === 'tamper') corrupted[corrupted.length - 1] ^= 1;
      session.sendRaw(corrupted);
    }
    await waitFor(() => session.closed, `${attack} disconnect`);
    assert.equal(f.client.send('text', { s: 'after invalid frame', ts: 3 }), false);
    assert.deepEqual(f.mac.messages, []);
    assert.ok(f.states.some(s => s.status === 'error' || s.status === 'disconnected'));
  });
}
