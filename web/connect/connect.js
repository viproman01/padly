import { normalizeReceiverAddress } from './address.js';

const form = document.getElementById('connect-form');
const address = document.getElementById('mac-address');
const message = document.getElementById('address-message');
const button = document.getElementById('connect-button');
let navigating = false;

address.addEventListener('input', () => {
  address.removeAttribute('aria-invalid');
  message.textContent = '';
  message.dataset.state = '';
  button.textContent = 'Открыть тачпад ↗';
  navigating = false;
});

form.addEventListener('submit', event => {
  event.preventDefault();
  if (navigating) return;
  try {
    const target = normalizeReceiverAddress(address.value);
    address.removeAttribute('aria-invalid');
    message.dataset.state = 'ready';
    message.textContent = `Открываем ${target.host}. Введите PIN уже на странице вашего компьютера.`;
    button.textContent = 'Открываем тачпад…';
    navigating = true;
    // A top-level navigation keeps the actual client on the receiver's origin.
    // Do not probe a private network address from this public page with fetch.
    window.location.assign(target.href);
  } catch (error) {
    navigating = false;
    address.setAttribute('aria-invalid', 'true');
    message.dataset.state = 'error';
    message.textContent = error.message;
    address.focus();
  }
});

window.addEventListener('pageshow', () => {
  navigating = false;
  button.textContent = 'Открыть тачпад ↗';
  if (message.dataset.state === 'ready') {
    message.dataset.state = '';
    message.textContent = '';
  }
});
