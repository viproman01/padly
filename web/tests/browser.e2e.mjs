import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { chromium } from 'playwright';
import { startMockMac } from './helpers/mock-mac.mjs';

// Real browser + independent Node crypto endpoint. This does not replace the
// macOS hardware smoke test: no events are injected into the host computer.
const resources = new URL('../../mac/Sources/Padly/BrowserResources/', import.meta.url);
const mac = await startMockMac({ resources });
const browser = await chromium.launch({
  headless: true,
  channel: process.env.PADLY_BROWSER_CHANNEL || undefined,
  args: ['--host-resolver-rules=MAP padly.test 127.0.0.1', '--no-proxy-server'],
});
let context;
let page;
const errors = [];
const pageErrors = [];
try {
  context = await browser.newContext({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
  page = await context.newPage();
  page.on('pageerror', e => pageErrors.push(e.message));
  page.on('console', msg => { if (msg.type() === 'error' && !msg.text().includes('404')) errors.push(msg.text()); });
  const url = mac.baseURL.replace('127.0.0.1', 'padly.test');
  await page.goto(url);
  await page.locator('#pairing').waitFor({ state: 'visible' });
  assert.equal(await page.evaluate(() => isSecureContext), false, 'must exercise LAN HTTP, not localhost secure exception');
  assert.equal(await page.evaluate(() => typeof crypto.subtle), 'undefined', 'must not require secure-context crypto');
  assert.equal(await page.locator('#left-button').isDisabled(), true);
  await page.locator('#pin').fill('000000');
  await page.locator('#pin-submit').click();
  await page.waitForFunction(() => document.querySelector('#pin-help').textContent.includes('Неверный'));
  await page.locator('#pin').fill(mac.pin);
  await page.locator('#pin-submit').click();
  await page.waitForFunction(() => document.querySelector('#status').dataset.state === 'ready');
  assert.equal(await page.locator('#pairing').isVisible(), false);

  const cdp = await context.newCDPSession(page);
  const bounds = await page.locator('#touchpad').boundingBox();
  const point = (id, x, y) => ({ id, x, y, radiusX: 3, radiusY: 3, force: 1 });
  const a = point(1, bounds.x + 80, bounds.y + 80);
  const b = point(2, bounds.x + 160, bounds.y + 90);
  async function touch(type, points) { await cdp.send('Input.dispatchTouchEvent', { type, touchPoints: points }); }
  async function waitMessages(predicate) {
    const end = Date.now() + 3000;
    while (!predicate()) {
      if (Date.now() > end) throw new Error('Expected browser input did not reach the endpoint');
      await new Promise(resolve => setTimeout(resolve, 10));
    }
  }

  let before = mac.messages.length;
  await touch('touchStart', [a]);
  await touch('touchMove', [{ ...a, x: a.x + 24, y: a.y + 12 }]);
  await touch('touchEnd', []);
  await waitMessages(() => mac.messages.slice(before).some(m => m.t === 'cursorMove'));
  assert.ok(!mac.messages.slice(before).some(m => m.t === 'cursorClick'), 'movement must not create a tap');

  before = mac.messages.length;
  await touch('touchStart', [a]);
  await touch('touchEnd', []);
  await waitMessages(() => mac.messages.length >= before + 2);
  assert.deepEqual(mac.messages.slice(before, before + 2).map(m => [m.t, m.d.btn, m.d.down]),
    [['cursorClick', 0, true], ['cursorClick', 0, false]]);

  before = mac.messages.length;
  await touch('touchStart', [a, b]);
  await touch('touchMove', [{ ...a, y: a.y + 30 }, { ...b, y: b.y + 30 }]);
  await touch('touchEnd', []);
  await waitMessages(() => mac.messages.slice(before).some(m => m.t === 'scroll'));
  assert.ok(!mac.messages.slice(before).some(m => m.t === 'cursorClick'), 'scroll must not create a tap');

  before = mac.messages.length;
  await touch('touchStart', [a, b]);
  await touch('touchEnd', []);
  await waitMessages(() => mac.messages.length >= before + 2);
  assert.deepEqual(mac.messages.slice(before, before + 2).map(m => [m.d.btn, m.d.down]), [[1, true], [1, false]]);

  // Hold a separate button with one finger, move on the pad with another.
  const left = await page.locator('#left-button').boundingBox();
  const held = point(3, left.x + 30, left.y + 20);
  before = mac.messages.length;
  await touch('touchStart', [held]);
  await touch('touchStart', [held, a]);
  await touch('touchMove', [held, { ...a, x: a.x + 25 }]);
  await touch('touchEnd', []);
  await waitMessages(() => mac.messages.slice(before).some(m => m.t === 'cursorClick' && !m.d.down));
  const drag = mac.messages.slice(before);
  assert.deepEqual([drag[0].t, drag[0].d.down, drag.at(-1).t, drag.at(-1).d.down], ['cursorClick', true, 'cursorClick', false]);
  assert.ok(drag.slice(1, -1).some(m => m.t === 'cursorMove'), 'drag must move before releasing');

  // Cancellation must always release an actual held button.
  before = mac.messages.length;
  await touch('touchStart', [held]);
  await touch('touchCancel', []);
  await waitMessages(() => mac.messages.length >= before + 2);
  assert.deepEqual(mac.messages.slice(before).map(m => [m.d.btn, m.d.down]), [[0, true], [0, false]]);

  await page.locator('#text-input').fill('Привет 👋');
  await page.locator('#text-submit').click();
  await waitMessages(() => mac.messages.some(m => m.t === 'text' && m.d.s === 'Привет 👋'));
  before = mac.messages.length;
  await page.locator('[data-key="42"]').click();
  await waitMessages(() => mac.messages.length >= before + 2);
  assert.deepEqual(mac.messages.slice(before).map(m => [m.t, m.d.code, m.d.down]), [['key', 42, true], ['key', 42, false]]);

  // Reload keeps the tab's pairing; it must resume without asking for a PIN.
  await page.reload();
  await page.waitForFunction(() => document.querySelector('#status').dataset.state === 'ready');
  assert.equal(mac.sessions.at(-1).known, true);
  assert.equal(await page.locator('#pairing').isVisible(), false);

  for (const size of [{ width: 320, height: 700 }, { width: 844, height: 390 }, { width: 1024, height: 768 }]) {
    await page.setViewportSize(size);
    assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `horizontal overflow at ${size.width}`);
  }
  await page.setViewportSize({ width: 390, height: 844 });
  if (process.env.PADLY_SCREENSHOTS) {
    await mkdir(process.env.PADLY_SCREENSHOTS, { recursive: true });
    await page.screenshot({ path: `${process.env.PADLY_SCREENSHOTS}/padly-browser.png`, fullPage: true });
  }
  await page.locator('#connection-button').click();
  await page.waitForFunction(() => document.querySelector('#status').dataset.state === 'disconnected');
  assert.equal(await page.locator('#left-button').isDisabled(), true);
  await page.locator('#connection-button').click();
  await page.waitForFunction(() => document.querySelector('#status').dataset.state === 'ready');
  assert.deepEqual(mac.errors, []);
  assert.deepEqual(pageErrors, []);
  assert.deepEqual(errors, []);
  console.log('BROWSER_OK: HTTP without crypto.subtle; PIN retry; encrypted touch/move/tap/scroll/right-click/drag/cancel/text/key; reload/resume; disconnect/reconnect; phone/landscape/tablet layout.');
} catch (error) {
  console.error(JSON.stringify({ pageErrors, errors, configRequests: mac.configRequests,
    serverErrors: mac.errors.map(e => e.message), sessions: mac.sessions.length,
    status: await page?.locator('#status-text').textContent().catch(() => null) }));
  throw error;
} finally {
  await context?.close();
  await browser.close();
  await mac.close();
}
