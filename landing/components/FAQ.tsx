'use client';

import { useState } from 'react';

const items = [
  {
    q: 'Будет ли версия для iPhone?',
    a: 'Возможно, позже. Сейчас приоритет — Android (где нет MacBook-зависимости от Apple-экосистемы). Если соберём 500+ беты-юзеров на Android, начнём iOS.',
  },
  {
    q: 'Работает ли без интернета?',
    a: 'Да. WiFi-режим работает в локальной сети (без выхода в интернет). Bluetooth-режим вообще не использует сеть — телефон превращается в стандартную BT-мышь.',
  },
  {
    q: 'Какая задержка?',
    a: 'На 5 GHz WiFi медиана ≤30 ms от тапа до клика. На Bluetooth ~50 ms. Для сравнения, у Magic Trackpad по Bluetooth ~30 ms — мы рядом.',
  },
  {
    q: 'Безопасно ли это?',
    a: 'TLS 1.3 с pinning сертификата, HMAC-SHA256 на каждом фрейме, 6-значный PIN при первой паре. Никаких облаков — всё локально. Код открытый, можно проверить.',
  },
  {
    q: 'Это open source?',
    a: 'Да, MIT-лицензия. Код на GitHub. Можно форкать, контрибьютить, собирать свою версию.',
  },
  {
    q: 'Нужен ли Mac App Store?',
    a: 'Нет. Раздаём прямой .dmg. На первом запуске macOS Gatekeeper может ругнуться — кликни правой кнопкой по Padly → Open, и он разрешит запуск.',
  },
  {
    q: 'Какие версии macOS поддерживаются?',
    a: 'macOS 14 (Sonoma) и новее. Используем `Network.framework` и SwiftUI MenuBarExtra, которые требуют свежей версии.',
  },
  {
    q: 'Какие версии Android?',
    a: 'Android 9 (API 28) и новее. Bluetooth HID API стабильно работает только с Android 9+.',
  },
];

export function FAQ() {
  return (
    <section id="faq" className="relative bg-ink-800/30 py-24">
      <div className="container-tight">
        <div className="mx-auto max-w-2xl text-center">
          <h2 className="text-3xl font-bold tracking-tight sm:text-4xl">FAQ</h2>
          <p className="mt-3 text-ink-400">
            Самые частые вопросы. Если чего-то нет — напиши на{' '}
            <a className="text-accent-light hover:underline" href="mailto:hi@padly.app">
              hi@padly.app
            </a>
            .
          </p>
        </div>

        <div className="mx-auto mt-12 max-w-3xl divide-y divide-ink-600/40 border-y border-ink-600/40">
          {items.map((it, i) => (
            <FaqItem key={i} q={it.q} a={it.a} />
          ))}
        </div>
      </div>
    </section>
  );
}

function FaqItem({ q, a }: { q: string; a: string }) {
  const [open, setOpen] = useState(false);
  return (
    <button
      type="button"
      onClick={() => setOpen((v) => !v)}
      className="block w-full py-5 text-left transition hover:bg-ink-800/40"
    >
      <div className="flex items-center justify-between gap-4 px-2">
        <span className="text-base font-semibold">{q}</span>
        <span
          aria-hidden
          className={`flex h-7 w-7 items-center justify-center rounded-full border border-ink-600/60 text-ink-400 transition ${
            open ? 'rotate-45 bg-accent/10 text-accent-light' : ''
          }`}
        >
          +
        </span>
      </div>
      {open && <p className="mt-3 px-2 text-sm text-ink-400">{a}</p>}
    </button>
  );
}
