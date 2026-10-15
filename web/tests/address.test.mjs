import test from 'node:test';
import assert from 'node:assert/strict';
import { normalizeMacAddress } from '../connect/address.js';

test('local IPv4 and mDNS receiver addresses become root HTTP URLs', () => {
  const cases = [
    ['192.168.1.25', 'http://192.168.1.25:47822/'],
    ['192.168.1.25:47822', 'http://192.168.1.25:47822/'],
    [' http://10.0.0.7:1234/ ', 'http://10.0.0.7:1234/'],
    ['HTTP://172.16.0.1', 'http://172.16.0.1:47822/'],
    ['172.31.255.254:65535/', 'http://172.31.255.254:65535/'],
    ['MacBook-Pro.local', 'http://macbook-pro.local:47822/'],
    ['http://office.mac.local:80/', 'http://office.mac.local/'],
    ['a.local:1', 'http://a.local:1/'],
  ];
  for (const [input, expected] of cases) {
    const result = normalizeMacAddress(input);
    assert.ok(result instanceof URL);
    assert.equal(result.href, expected, input);
  }
});

test('only a private LAN host is accepted, without alternate IP spellings', () => {
  const rejected = [
    '127.0.0.1', 'localhost', 'foo.localhost', '0.0.0.0', '8.8.8.8', '109.172.47.162',
    '172.15.1.2', '172.32.1.2', '192.169.1.1', '169.254.1.1', '100.64.0.1',
    '10.999.0.1', '10.0.0', '10.0.0.01', '010.0.0.1', '0x0a000001', '167772161',
    '[::1]', '[fd00::1]', 'example.com', 'mac.local.example.com', 'local', '.local',
    '-mac.local', 'mac-.local', 'mac..local', 'mac_name.local', `${'a'.repeat(64)}.local`,
  ];
  for (const input of rejected) assert.throws(() => normalizeMacAddress(input), Error, input);
});

test('schemes, credentials, non-root locations and port ambiguity are rejected', () => {
  const rejected = [
    'https://192.168.1.25', 'ws://192.168.1.25:47821', 'javascript:alert(1)',
    '//192.168.1.25', 'http:192.168.1.25', 'http:///192.168.1.25',
    'http://name@192.168.1.25/', 'http://name:password@192.168.1.25/',
    '192.168.1.25/app', '192.168.1.25//', '192.168.1.25/..',
    '192.168.1.25?pin=123456', '192.168.1.25#123456', '192.168.1.25?',
    'http://192.168.1.25\\evil', '192.168.1.25:0', '192.168.1.25:65536',
    '192.168.1.25:-1', '192.168.1.25:abc', '192.168.1.25:', '192.168.1.25:1.5',
    '192.168.1.25: 47822', '192.168.1.25\n.evil.local', 'mac%2elocal',
  ];
  for (const input of rejected) assert.throws(() => normalizeMacAddress(input), Error, input);
});

test('missing address gives an actionable Russian error', () => {
  for (const input of [undefined, null, '', '   ', 123]) {
    assert.throws(() => normalizeMacAddress(input), /адрес.*Padly/);
  }
});
