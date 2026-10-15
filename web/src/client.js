import { Handshake, SecureChannel, identity, pack, unpack, pinProof } from './protocol.js';

const STORAGE_KEY = 'padly.browser.pair.v2';
function safeStorage() {
  try { return globalThis.sessionStorage; } catch { return null; }
}

export class PadlyClient {
  constructor({ onState = () => {}, baseURL = globalThis.location?.origin,
    storage = safeStorage(), WebSocketImpl = globalThis.WebSocket,
    fetchImpl = globalThis.fetch?.bind(globalThis) } = {}) {
    Object.assign(this, { onState, baseURL, storage, WebSocketImpl, fetchImpl });
    this.status = 'disconnected';
    this.generation = 0;
    this.pair = null;
    try {
      const saved = JSON.parse(storage?.getItem(STORAGE_KEY) ?? 'null');
      if (saved && typeof saved.deviceId === 'string' && typeof saved.serverId === 'string' &&
        Array.isArray(saved.ltk) && saved.ltk.length === 32 &&
        saved.ltk.every(b => Number.isInteger(b) && b >= 0 && b <= 255)) this.pair = saved;
    } catch { /* private mode / corrupt storage: pair anew */ }
  }

  emit(status, message, extra = {}) {
    this.status = status;
    this.onState({ status, message, name: this.name, ...extra });
  }

  async connect() {
    this.close(false);
    const generation = this.generation;
    this.emit('connecting', 'Подключаемся к компьютеру…');
    this.abort = new AbortController();
    this.timeout = setTimeout(() => this.fail('Компьютер не ответил. Проверьте Wi-Fi и приложение Padly.'), 10000);
    try {
      const base = new URL(this.baseURL);
      if (base.protocol !== 'http:') throw new Error('Откройте HTTP-адрес из Padly на компьютере.');
      const response = await this.fetchImpl(new URL('/config.json', base), {
        cache: 'no-store', credentials: 'omit', signal: this.abort.signal,
      });
      if (!response.ok) throw new Error('Не удалось получить настройки компьютера.');
      const config = await response.json();
      if (generation !== this.generation) return;
      if (!Number.isInteger(config.wsPort) || config.wsPort < 1 || config.wsPort > 65535 ||
        typeof config.serverId !== 'string' || !config.serverId) throw new Error('Неверные настройки компьютера.');
      this.serverId = config.serverId;
      this.activePair = this.pair?.serverId === config.serverId ? this.pair : null;
      this.deviceId = this.activePair?.deviceId ?? identity();
      this.handshake = new Handshake();
      const wsURL = new URL(base);
      wsURL.protocol = 'ws:';
      wsURL.port = String(config.wsPort);
      wsURL.pathname = '/';
      wsURL.search = '';
      wsURL.hash = '';
      const socket = new this.WebSocketImpl(wsURL.href);
      this.socket = socket;
      socket.binaryType = 'arraybuffer';
      socket.onopen = () => {
        if (generation !== this.generation) return;
        socket.send(pack('helloE', { deviceId: this.deviceId,
          deviceName: 'Браузер Padly', appVer: '0.1.0-web',
          caps: ['browser', 'key', 'text', 'cursor'], pub: this.handshake.publicKey }));
      };
      socket.onmessage = event => {
        if (generation !== this.generation) return;
        try {
          if (!(event.data instanceof ArrayBuffer)) throw new Error('Ожидался бинарный пакет.');
          this.receive(new Uint8Array(event.data));
        } catch { this.fail('Проверка защищённого соединения не прошла. Подключитесь заново.'); }
      };
      socket.onerror = () => {
        if (generation === this.generation) this.fail('Нет соединения. Проверьте Wi-Fi, Padly и доступ к локальной сети.');
      };
      socket.onclose = () => {
        if (generation !== this.generation) return;
        this.cleanup();
        this.emit('disconnected', 'Соединение закрыто. Нажмите «Подключиться».');
      };
    } catch (error) {
      if (generation === this.generation) this.fail(error.message || 'Не удалось подключиться.');
    }
  }

  receive(bytes) {
    if (!this.recvChannel) {
      const msg = unpack(bytes);
      if (msg.t !== 'welcomeE' || msg.d.serverId !== this.serverId || typeof msg.d.known !== 'boolean') {
        throw new Error('Unexpected handshake');
      }
      if (msg.d.known && !this.activePair) throw new Error('Missing saved key');
      this.name = String(msg.d.name ?? 'компьютеру');
      this.sessionKey = this.handshake.derive(msg.d.pub,
        msg.d.known ? Uint8Array.from(this.activePair.ltk) : null);
      this.sendChannel = new SecureChannel(this.sessionKey, 1);
      this.recvChannel = new SecureChannel(this.sessionKey, 2);
      this.expectPin = !msg.d.known;
      return;
    }
    const msg = unpack(this.recvChannel.open(bytes));
    switch (msg.t) {
      case 'welcome':
        if (this.status !== 'connecting' || !msg.d.accepted) throw new Error('Unexpected welcome');
        if (this.expectPin) {
          if (!msg.d.requirePin) throw new Error('Missing PIN request');
          this.armPinTimeout();
          this.emit('pin', 'Введите PIN из Padly на компьютере.');
        } else {
          if (msg.d.requirePin) throw new Error('Unexpected PIN request');
          this.ready();
        }
        break;
      case 'pairOk':
        if (this.status !== 'pin' || !(msg.d.ltk instanceof Uint8Array) || msg.d.ltk.length !== 32) {
          throw new Error('Unexpected pairing result');
        }
        this.pair = { serverId: this.serverId, deviceId: this.deviceId, ltk: Array.from(msg.d.ltk) };
        try { this.storage?.setItem(STORAGE_KEY, JSON.stringify(this.pair)); } catch { /* tab memory works */ }
        this.ready();
        break;
      case 'pairFail': {
        const reason = msg.d.reason;
        if (reason === 'bad-pin' && msg.d.left > 0 && this.status === 'pin') {
          this.armPinTimeout();
          this.emit('pin', `Неверный PIN. Осталось попыток: ${msg.d.left}.`, { attemptsLeft: msg.d.left });
        } else {
          const message = reason === 'locked' ? 'Слишком много попыток. Подождите минуту и создайте новый PIN на компьютере.'
            : reason === 'expired' ? 'PIN истёк. Создайте новый PIN на компьютере и подключитесь заново.'
              : 'Откройте подключение телефона в Padly на компьютере, затем подключитесь заново.';
          this.fail(message);
        }
        break;
      }
      case 'pong': this.lastPong = Date.now(); break;
      case 'bye': this.fail('Компьютер завершил соединение. Подключитесь заново.'); break;
      default: throw new Error('Unexpected message');
    }
  }

  ready() {
    clearTimeout(this.timeout);
    this.lastPong = Date.now();
    this.emit('ready', `Подключено к ${this.name}`);
    this.pingTimer = setInterval(() => {
      if (Date.now() - this.lastPong > 12000) this.fail('Компьютер перестал отвечать. Подключитесь заново.');
      else this.send('ping', { ts: Date.now() });
    }, 3000);
  }

  submitPin(pin) {
    if (this.status !== 'pin') return false;
    try {
      const proof = pinProof(this.sessionKey, pin);
      clearTimeout(this.timeout);
      this.timeout = setTimeout(() => this.fail('Компьютер не подтвердил PIN. Проверьте Wi-Fi и подключитесь заново.'), 10000);
      return this.sendSealed('pin', { proof });
    }
    catch (error) { this.emit('pin', error.message); return false; }
  }

  armPinTimeout() {
    clearTimeout(this.timeout);
    this.timeout = setTimeout(() => this.fail('PIN истёк. Создайте новый PIN на компьютере и подключитесь заново.'), 120000);
  }

  send(type, fields) {
    if (this.status !== 'ready' || !['cursorMove', 'cursorClick', 'scroll', 'text', 'key', 'ping'].includes(type)) return false;
    return this.sendSealed(type, fields);
  }

  sendSealed(type, fields) {
    if (!this.sendChannel || this.socket?.readyState !== 1) return false;
    // Bound latency and memory on a stalled radio. Closing also releases held
    // buttons on the receiver; a mouse-up must never be silently dropped.
    if (this.socket.bufferedAmount > 65536) { this.fail('Сеть перегружена. Подключитесь заново.'); return false; }
    try { this.socket.send(this.sendChannel.seal(pack(type, fields))); return true; }
    catch { this.fail('Не удалось отправить событие. Подключитесь заново.'); return false; }
  }

  cleanup() {
    clearTimeout(this.timeout);
    clearInterval(this.pingTimer);
    this.abort?.abort();
    this.sendChannel = this.recvChannel = this.sessionKey = this.handshake = null;
  }

  close(notify = true) {
    this.generation++;
    const socket = this.socket;
    if (socket?.readyState === 1 && this.sendChannel) {
      try { socket.send(this.sendChannel.seal(pack('bye', { reason: 'browser-close' }))); } catch { /* already gone */ }
    }
    this.socket = null;
    this.cleanup();
    if (socket) {
      socket.onopen = socket.onmessage = socket.onclose = socket.onerror = null;
      socket.close();
    }
    this.status = 'disconnected';
    if (notify) this.emit('disconnected', 'Отключено. Нажмите «Подключиться».');
  }

  fail(message) {
    this.close(false);
    this.emit('error', message);
  }
}
