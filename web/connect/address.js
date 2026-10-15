const ADDRESS_ERROR = 'Введите локальный адрес из Padly: например, 192.168.1.25:47822. Устройства должны быть в одной сети Wi-Fi.';

/** Convert an explicitly entered LAN receiver address into its local page URL. */
export function normalizeReceiverAddress(input) {
  if (typeof input !== 'string' || !input.trim()) {
    throw new Error('Скопируйте адрес из Padly на компьютере.');
  }
  const text = input.trim();
  // Parse the spelling before URL canonicalization: URLs accept alternate IPv4
  // encodings, backslashes, credentials and fragments which are unwanted here.
  const match = /^(?:http:\/\/)?([^\/:?#\s@\\]+)(?::([0-9]+))?\/?$/i.exec(text);
  if (!match) {
    throw new Error('Нужен HTTP-адрес компьютера без логина, пути, параметров или PIN.');
  }
  const host = match[1].toLowerCase();
  const port = match[2] === undefined ? 47822 : Number(match[2]);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error('Порт должен быть числом от 1 до 65535. Возьмите его из меню Padly.');
  }

  let localHost = false;
  if (/^\d+\.\d+\.\d+\.\d+$/.test(host)) {
    const parts = host.split('.');
    if (parts.every(part => /^(?:0|[1-9]\d{0,2})$/.test(part) && Number(part) <= 255)) {
      const [a, b] = parts.map(Number);
      localHost = a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
    }
  } else if (host.length <= 253 && host.endsWith('.local')) {
    const labels = host.slice(0, -6).split('.');
    localHost = labels.every(label => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(label));
  }
  if (!localHost) throw new Error(ADDRESS_ERROR);
  return new URL(`http://${host}:${port}/`);
}

// Keep compatibility with integrations that used the original Mac-only name.
export const normalizeMacAddress = normalizeReceiverAddress;
