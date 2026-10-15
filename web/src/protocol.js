import { encode, decode } from '@msgpack/msgpack';
import { p256 } from '@noble/curves/p256';
import { hkdf } from '@noble/hashes/hkdf';
import { hmac } from '@noble/hashes/hmac';
import { sha256 } from '@noble/hashes/sha256';
import { gcm } from '@noble/ciphers/aes';

const utf8 = new TextEncoder();
export const pack = (type, fields) => encode({ t: type, d: fields });
export function unpack(bytes) {
  const msg = decode(bytes, { maxStrLength: 16384, maxBinLength: 65536,
    maxArrayLength: 128, maxMapLength: 128 });
  if (!msg || typeof msg.t !== 'string' || !msg.d || typeof msg.d !== 'object') {
    throw new Error('Некорректное сообщение от компьютера');
  }
  return msg;
}

export function concat(...parts) {
  const result = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let offset = 0;
  for (const part of parts) { result.set(part, offset); offset += part.length; }
  return result;
}

// getRandomValues works on local HTTP origins too; crypto.subtle does not.
// Bundle the audited primitives locally so a LAN client needs neither HTTPS
// certificates, a CDN, nor an insecure Math.random fallback.
export function randomBytes(length) {
  return globalThis.crypto.getRandomValues(new Uint8Array(length));
}
export function identity() {
  return 'browser-' + Array.from(randomBytes(16), b => b.toString(16).padStart(2, '0')).join('');
}

export class Handshake {
  constructor(privateKey = p256.utils.randomPrivateKey()) {
    this.privateKey = privateKey;
    this.publicKey = p256.getPublicKey(privateKey, false);
  }
  derive(serverPub, ltk = null) {
    if (!(serverPub instanceof Uint8Array) || serverPub.length !== 65 || serverPub[0] !== 4) {
      throw new Error('Некорректный ключ компьютера');
    }
    // ECDH uses only the affine X coordinate, matching CryptoKit / Java ECDH.
    const shared = p256.getSharedSecret(this.privateKey, serverPub, false).slice(1, 33);
    return hkdf(sha256, shared, utf8.encode(ltk ? 'padly-resume-v2' : 'padly-pair-v2'),
      concat(this.publicKey, serverPub, ...(ltk ? [ltk] : [])), 32);
  }
}

export function pinProof(key, pin) {
  if (!/^\d{6}$/.test(pin)) throw new Error('Введите 6 цифр PIN');
  return hmac(sha256, key, utf8.encode('padly-pin-v2' + pin));
}

export class SecureChannel {
  constructor(key, direction) {
    this.key = key;
    this.direction = direction;
    this.sendSeq = 1;
    this.recvSeq = 0;
  }
  seal(payload) {
    if (this.sendSeq > 0xffffffff) throw new Error('Сессия истекла. Подключитесь заново.');
    const seq = this.sendSeq++;
    const aad = new Uint8Array(5);
    aad[0] = 2;
    new DataView(aad.buffer).setUint32(1, seq, true);
    const nonce = new Uint8Array(12);
    nonce[0] = this.direction;
    nonce.set(aad.subarray(1), 4);
    return concat(aad, nonce, gcm(this.key, nonce, aad).encrypt(payload));
  }
  open(frame) {
    if (!(frame instanceof Uint8Array) || frame.length < 33 || frame.length > 65536 || frame[0] !== 2) {
      throw new Error('Некорректный зашифрованный пакет');
    }
    const seq = new DataView(frame.buffer, frame.byteOffset, frame.byteLength).getUint32(1, true);
    if (seq <= this.recvSeq) throw new Error('Повтор пакета');
    const plain = gcm(this.key, frame.subarray(5, 17), frame.subarray(0, 5)).decrypt(frame.subarray(17));
    this.recvSeq = seq;
    return plain;
  }
}
