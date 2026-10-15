import { PadlyClient } from './client.js';
import { GestureTracker } from './gestures.js';

const byId = id => document.getElementById(id);
const touchpad = byId('touchpad');
const connectionButton = byId('connection-button');
const pinForm = byId('pin-form');
const pinInput = byId('pin');
const pinSubmit = byId('pin-submit');
const textInput = byId('text-input');
const leftButton = byId('left-button');
const rightButton = byId('right-button');
const sensitivityInput = byId('sensitivity');
let ready = false;
let connectionState = 'disconnected';
let pending = [];
let frame = 0;
let sensitivity = 1.5;
let mousePointer = null;
const heldButtons = new Map();

function flushMotion() {
  if (frame) cancelAnimationFrame(frame);
  frame = 0;
  const batch = pending;
  pending = [];
  if (!ready) return;
  for (const item of batch) {
    client.send(item.type, { dx: item.dx, dy: item.dy, ...(item.type === 'scroll' ? { momentum: false } : {}), ts: Date.now() });
  }
}

function send(type, fields) {
  if (!ready) return false;
  // Keep the order of moves, button transitions, text and keys intact.
  flushMotion();
  return client.send(type, { ...fields, ts: Date.now() });
}

function queueMotion(type, dx, dy) {
  if (!ready || (!dx && !dy)) return;
  const last = pending[pending.length - 1];
  if (last && last.type === type) {
    last.dx += dx;
    last.dy += dy;
  } else {
    pending.push({ type, dx, dy });
  }
  if (!frame) frame = requestAnimationFrame(flushMotion);
}

function click(button) {
  // A separate finger can tap while the left mouse button is held to drag.
  if (heldButtons.has(button)) return;
  if (send('cursorClick', { btn: button, down: true })) send('cursorClick', { btn: button, down: false });
}

const gestures = new GestureTracker(action => {
  if (action.type === 'tap') click(action.button);
  else if (action.type === 'move') queueMotion('cursorMove', action.dx * sensitivity, action.dy * sensitivity);
  else if (action.type === 'scroll') queueMotion('scroll', action.dx, action.dy);
});

function releaseButton(button) {
  const held = heldButtons.get(button);
  if (!held) return;
  send('cursorClick', { btn: button, down: false });
  held.element.dataset.pressed = 'false';
  held.element.setAttribute('aria-pressed', 'false');
  heldButtons.delete(button);
}

function releaseAll() {
  flushMotion();
  for (const button of [...heldButtons.keys()]) releaseButton(button);
  gestures.reset();
  mousePointer = null;
  touchpad.dataset.active = 'false';
}

function setState(state) {
  if (state.status !== 'ready') releaseAll();
  connectionState = state.status;
  ready = state.status === 'ready';
  const labels = {
    connecting: 'Подключаемся к компьютеру…', pin: 'Нужен PIN с экрана компьютера',
    ready: state.name ? `Подключено к ${state.name}` : 'Подключено к компьютеру',
    disconnected: 'Нет подключения к компьютеру', error: 'Не удалось подключиться',
  };
  byId('status').dataset.state = state.status;
  byId('status-text').textContent = state.message || labels[state.status] || 'Нет подключения';
  connectionButton.textContent = ready || state.status === 'pin' || state.status === 'connecting' ? 'Отключить' : 'Подключить снова';
  byId('pairing').hidden = state.status !== 'pin';
  pinSubmit.disabled = false;
  pinInput.disabled = false;
  if (state.status === 'pin') {
    pinInput.value = '';
    const attempts = Number.isInteger(state.attemptsLeft) ? ` Осталось попыток: ${state.attemptsLeft}.` : '';
    byId('pin-help').textContent = (state.message || 'Откройте Padly на компьютере — код появится в окне подключения.') + attempts;
  }
  touchpad.dataset.enabled = String(ready);
  touchpad.setAttribute('aria-disabled', String(!ready));
  byId('surface-state').textContent = ready ? 'ГОТОВ К ЖЕСТАМ' : state.status === 'connecting' ? 'ПОДКЛЮЧЕНИЕ…' : 'ОЖИДАНИЕ ПК';
  byId('surface-label').textContent = ready ? 'Место для ваших жестов' : 'Подключите компьютер, чтобы начать';
  for (const element of [leftButton, rightButton, textInput, byId('text-submit'), ...document.querySelectorAll('[data-key]')]) element.disabled = !ready;
}

const client = new PadlyClient({ onState: setState });

async function connect() {
  try {
    await client.connect();
  } catch {
    setState({ status: 'error', message: 'Компьютер недоступен. Проверьте Padly и подключение к одной сети Wi-Fi.' });
  }
}

connectionButton.addEventListener('click', () => {
  if (ready || connectionState === 'connecting' || connectionState === 'pin') {
    releaseAll();
    client.close();
    setState({ status: 'disconnected', message: 'Подключение завершено' });
  } else connect();
});

pinInput.addEventListener('input', () => { pinInput.value = pinInput.value.replace(/\D/g, '').slice(0, 6); });
pinForm.addEventListener('submit', async event => {
  event.preventDefault();
  if (!/^[0-9]{6}$/.test(pinInput.value) || pinSubmit.disabled) return;
  pinSubmit.disabled = true;
  pinInput.disabled = true;
  try {
    await client.submitPin(pinInput.value);
  } catch {
    setState({ status: 'error', message: 'Не удалось проверить PIN. Подключитесь снова.' });
  } finally {
    pinSubmit.disabled = false;
    pinInput.disabled = false;
  }
});

function pointsFromTouch(event) {
  return Array.from(event.touches).filter(touch => touchpad.contains(touch.target)).map(touch => ({ id: touch.identifier, x: touch.clientX, y: touch.clientY }));
}

for (const [eventName, phase] of [['touchstart', 'start'], ['touchmove', 'move'], ['touchend', 'end'], ['touchcancel', 'cancel']]) {
  touchpad.addEventListener(eventName, event => {
    event.preventDefault();
    if (!ready) return;
    const points = pointsFromTouch(event);
    if (phase === 'cancel') {
      releaseAll();
      return;
    }
    gestures.update(phase, points, Date.now());
    touchpad.dataset.active = String(points.length > 0 && phase !== 'cancel');
    if (phase === 'end' || phase === 'cancel') flushMotion();
  }, { passive: false });
}

// Mouse/pen also work when this page is opened on a computer or tablet.
touchpad.addEventListener('pointerdown', event => {
  if (event.pointerType === 'touch' || !ready || event.button !== 0) return;
  event.preventDefault();
  mousePointer = event.pointerId;
  touchpad.setPointerCapture(event.pointerId);
  gestures.update('start', [{ id: event.pointerId, x: event.clientX, y: event.clientY }]);
  touchpad.dataset.active = 'true';
});
touchpad.addEventListener('pointermove', event => {
  if (event.pointerId !== mousePointer) return;
  gestures.update('move', [{ id: event.pointerId, x: event.clientX, y: event.clientY }]);
});
for (const eventName of ['pointerup', 'pointercancel', 'lostpointercapture']) {
  touchpad.addEventListener(eventName, event => {
    if (event.pointerId !== mousePointer) return;
    if (eventName === 'pointercancel') {
      releaseAll();
      return;
    }
    gestures.update(eventName === 'pointerup' ? 'end' : 'cancel', []);
    mousePointer = null;
    touchpad.dataset.active = 'false';
    flushMotion();
  });
}
touchpad.addEventListener('contextmenu', event => event.preventDefault());
touchpad.addEventListener('wheel', event => {
  event.preventDefault();
  const factor = event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? touchpad.clientHeight : 1;
  queueMotion('scroll', -event.deltaX * factor, -event.deltaY * factor);
}, { passive: false });

function setupMouseButton(element, button) {
  element.setAttribute('aria-pressed', 'false');
  element.addEventListener('pointerdown', event => {
    if (!ready || event.button !== 0 || heldButtons.has(button)) return;
    event.preventDefault();
    if (!send('cursorClick', { btn: button, down: true })) return;
    heldButtons.set(button, { pointerId: event.pointerId, element });
    element.setPointerCapture(event.pointerId);
    element.dataset.pressed = 'true';
    element.setAttribute('aria-pressed', 'true');
  });
  for (const eventName of ['pointerup', 'pointercancel', 'lostpointercapture']) element.addEventListener(eventName, event => {
    if (heldButtons.get(button)?.pointerId === event.pointerId) releaseButton(button);
  });
  element.addEventListener('contextmenu', event => event.preventDefault());
  // Native keyboard and assistive-technology activation have no pointerdown.
  element.addEventListener('click', event => { if (event.detail === 0) click(button); });
}
setupMouseButton(leftButton, 0);
setupMouseButton(rightButton, 1);

function updateSensitivity() {
  sensitivity = Number(sensitivityInput.value);
  byId('sensitivity-value').textContent = `${sensitivity.toFixed(1).replace('.', ',')}×`;
  try { localStorage.setItem('padly.sensitivity', String(sensitivity)); } catch { /* Private browsing may disable storage. */ }
}
try {
  const saved = Number(localStorage.getItem('padly.sensitivity'));
  if (saved >= 0.5 && saved <= 3) sensitivityInput.value = String(saved);
} catch { /* The touchpad also works without local storage. */ }
updateSensitivity();
sensitivityInput.addEventListener('input', updateSensitivity);

byId('text-form').addEventListener('submit', event => {
  event.preventDefault();
  if (textInput.value && send('text', { s: textInput.value })) {
    textInput.value = '';
    textInput.focus();
  }
});
for (const button of document.querySelectorAll('[data-key]')) button.addEventListener('click', () => {
  const code = Number(button.dataset.key);
  if (send('key', { code, mods: 0, down: true })) send('key', { code, mods: 0, down: false });
});

window.addEventListener('blur', releaseAll);
document.addEventListener('visibilitychange', () => { if (document.hidden) releaseAll(); });
window.addEventListener('pagehide', () => { releaseAll(); client.close(); });
connect();
