import http from 'node:http';
import { createCipheriv, createDecipheriv, createECDH, createHmac, hkdfSync, randomBytes, timingSafeEqual } from 'node:crypto';
import { once } from 'node:events';
import { encode, decode } from '@msgpack/msgpack';
import { WebSocketServer } from 'ws';
import { readFileSync } from 'node:fs';

// Deliberately independent of web/src/protocol.js: this endpoint implements the
// published v2 wire format with Node crypto, as the Swift Mac endpoint does.
const INPUT_TYPES = new Set(['cursorMove', 'cursorClick', 'scroll', 'gesture', 'key', 'text', 'presenter']);

export class MemoryStorage {
  #values = new Map();
  get length() { return this.#values.size; }
  key(index) { return [...this.#values.keys()][index] ?? null; }
  getItem(key) { return this.#values.get(String(key)) ?? null; }
  setItem(key, value) { this.#values.set(String(key), String(value)); }
  removeItem(key) { this.#values.delete(String(key)); }
  clear() { this.#values.clear(); }
}

function envelope(type, fields) {
  return Buffer.from(encode({ t: type, d: fields }));
}

function seal(session, type, fields) {
  const seq = session.sendSeq++;
  const header = Buffer.alloc(5);
  header[0] = 2;
  header.writeUInt32LE(seq, 1);
  const nonce = Buffer.alloc(12);
  nonce[0] = 2;
  nonce.writeUInt32LE(seq, 4);
  const cipher = createCipheriv('aes-256-gcm', session.key, nonce);
  cipher.setAAD(header);
  const encrypted = Buffer.concat([cipher.update(envelope(type, fields)), cipher.final()]);
  return Buffer.concat([header, nonce, encrypted, cipher.getAuthTag()]);
}

function open(session, frame) {
  if (frame.length < 33 || frame[0] !== 2) throw new Error('invalid sealed frame');
  const seq = frame.readUInt32LE(1);
  if (seq <= session.lastRecvSeq) throw new Error('replayed client frame');
  const nonce = frame.subarray(5, 17);
  const expectedNonce = Buffer.alloc(12);
  expectedNonce[0] = 1;
  expectedNonce.writeUInt32LE(seq, 4);
  if (!nonce.equals(expectedNonce)) throw new Error('incorrect client direction/nonce');
  const decipher = createDecipheriv('aes-256-gcm', session.key, nonce);
  decipher.setAAD(frame.subarray(0, 5));
  decipher.setAuthTag(frame.subarray(-16));
  const plain = Buffer.concat([decipher.update(frame.subarray(17, -16)), decipher.final()]);
  session.lastRecvSeq = seq;
  return decode(plain);
}

export async function startMockMac({ pin = '123456', expired = false, serverId = 'independent-mac-v2', resources = null, dropPinResponse = false } = {}) {
  const mock = {
    pin, expired, serverId, paired: new Map(), sessions: [], messages: [], errors: [], configRequests: [],
  };
  const sockets = new WebSocketServer({ host: '127.0.0.1', port: 0 });
  await once(sockets, 'listening');
  const wsPort = sockets.address().port;
  const httpServer = http.createServer((request, response) => {
    mock.configRequests.push(request.url);
    if (request.url === '/config.json') {
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ wsPort, serverId }));
    } else if (resources && ['/', '/index.html', '/app.js', '/styles.css'].includes(request.url)) {
      const name = request.url === '/' ? 'index.html' : request.url.slice(1);
      const body = readFileSync(new URL(name, resources));
      response.writeHead(200, {
        'Content-Type': name.endsWith('.js') ? 'text/javascript' : name.endsWith('.css') ? 'text/css' : 'text/html',
        'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self' ws:; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
      });
      response.end(body);
    } else {
      response.writeHead(404);
      response.end('Not found');
    }
  });
  httpServer.listen(0, '127.0.0.1');
  await once(httpServer, 'listening');
  mock.baseURL = `http://127.0.0.1:${httpServer.address().port}`;
  mock.wsPort = wsPort;

  sockets.on('connection', socket => {
    const session = {
      socket, key: null, sendSeq: 1, lastRecvSeq: 0, pinAttempts: 0,
      authenticated: false, hello: null, known: false, lastFrame: null, closed: false,
      frame(type, fields = {}) { return seal(this, type, fields); },
      send(type, fields = {}) {
        const frame = this.frame(type, fields);
        this.lastFrame = frame;
        socket.send(frame);
        return frame;
      },
      sendRaw(frame) { socket.send(frame); },
    };
    mock.sessions.push(session);
    socket.on('close', () => { session.closed = true; });
    socket.on('message', (bytes, isBinary) => {
      try {
        if (!isBinary) throw new Error('v2 messages must be binary');
        const frame = Buffer.from(bytes);
        if (!session.key) {
          const hello = decode(frame);
          if (hello.t !== 'helloE' || typeof hello.d?.deviceId !== 'string') throw new Error('missing helloE');
          const clientPub = Buffer.from(hello.d.pub ?? []);
          if (clientPub.length !== 65 || clientPub[0] !== 4) throw new Error('invalid X9.63 public key');
          session.hello = hello.d;
          const ecdh = createECDH('prime256v1');
          const serverPub = ecdh.generateKeys();
          const ltk = mock.paired.get(hello.d.deviceId);
          session.known = Boolean(ltk);
          const salt = Buffer.from(ltk ? 'padly-resume-v2' : 'padly-pair-v2');
          const info = Buffer.concat([clientPub, serverPub, ...(ltk ? [ltk] : [])]);
          session.key = Buffer.from(hkdfSync('sha256', ecdh.computeSecret(clientPub), salt, info, 32));
          socket.send(envelope('welcomeE', { serverId, name: 'Independent mock Mac', serverVer: '0.1.0', pub: serverPub, known: session.known }));
          session.authenticated = session.known;
          session.send('welcome', { accepted: true, requirePin: !session.known });
          return;
        }

        const message = open(session, frame);
        if (message.t === 'pin' && !session.authenticated) {
          if (dropPinResponse) return;
          session.pinAttempts++;
          if (mock.expired) {
            session.send('pairFail', { reason: 'expired', left: 0 });
            socket.close(1000, 'expired');
            return;
          }
          const proof = Buffer.from(message.d?.proof ?? []);
          const expected = createHmac('sha256', session.key).update('padly-pin-v2').update(mock.pin).digest();
          if (proof.length === expected.length && timingSafeEqual(proof, expected)) {
            const ltk = randomBytes(32);
            mock.paired.set(session.hello.deviceId, ltk);
            session.authenticated = true;
            session.send('pairOk', { ltk });
          } else {
            const left = Math.max(0, 5 - session.pinAttempts);
            session.send('pairFail', { reason: left ? 'bad-pin' : 'locked', left });
            if (!left) socket.close(1000, 'locked');
          }
          return;
        }
        if (!session.authenticated) throw new Error(`unauthenticated input: ${message.t}`);
        if (message.t === 'ping') {
          session.send('pong', { ts: message.d.ts, serverTs: Date.now() });
        } else if (message.t === 'bye') {
          socket.close(1000, 'bye');
        } else if (INPUT_TYPES.has(message.t)) {
          mock.messages.push({ ...message, deviceId: session.hello.deviceId });
        } else {
          throw new Error(`unexpected input type: ${message.t}`);
        }
      } catch (error) {
        mock.errors.push(error);
        socket.close(1002, 'invalid client frame');
      }
    });
  });

  mock.close = async () => {
    for (const socket of sockets.clients) socket.terminate();
    await Promise.all([
      new Promise(resolve => sockets.close(resolve)),
      new Promise(resolve => { httpServer.close(resolve); httpServer.closeAllConnections(); }),
    ]);
  };
  return mock;
}
