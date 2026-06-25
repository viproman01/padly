'use client';

import { motion } from 'framer-motion';

const features = [
  {
    title: 'Мульти-тач жесты',
    desc: '3-4 пальца открывают Mission Control, листают Spaces, открывают Launchpad. Pinch-to-zoom и rotate работают как на родном трекпаде.',
    icon: GestureIcon,
  },
  {
    title: 'Полная клавиатура',
    desc: 'cmd, opt, ctrl, shift, fn — все модификаторы. Русская раскладка, спецсимволы, F-клавиши. HID-протокол → 100% совместимость с macOS.',
    icon: KeyboardIcon,
  },
  {
    title: 'Презентер-режим',
    desc: 'Стрелки для слайдов, красная лазер-точка через overlay, таймер выступления. Авто-детект Keynote и PowerPoint.',
    icon: PresenterIcon,
  },
  {
    title: 'WiFi или Bluetooth',
    desc: 'Низкая задержка (<30 ms на 5 GHz). Bluetooth HID работает без интернета — телефон превращается в стандартную BT-мышь.',
    icon: NetworkIcon,
  },
  {
    title: 'Локально и безопасно',
    desc: 'TLS 1.3 с pinning, HMAC-SHA256 на каждом фрейме, 6-значный PIN при первом подключении. Никаких облаков и серверов.',
    icon: LockIcon,
  },
  {
    title: 'Open source',
    desc: 'Код целиком на GitHub под MIT. Аудитируй, форкай, контрибьюти. Свой ключ → свой сертификат → свой контроль.',
    icon: CodeIcon,
  },
];

export function Features() {
  return (
    <section id="features" className="relative py-24">
      <div className="container-tight">
        <div className="mx-auto max-w-2xl text-center">
          <h2 className="text-3xl font-bold tracking-tight sm:text-4xl">
            Что внутри
          </h2>
          <p className="mt-3 text-ink-400">
            Полный набор того, что есть в Magic Trackpad, плюс пара вещей,
            которых там нет.
          </p>
        </div>

        <div className="mt-14 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {features.map((f, i) => {
            const Icon = f.icon;
            return (
              <motion.div
                key={f.title}
                initial={{ opacity: 0, y: 16 }}
                whileInView={{ opacity: 1, y: 0 }}
                viewport={{ once: true, margin: '-50px' }}
                transition={{ duration: 0.45, delay: i * 0.05 }}
                className="surface p-6"
              >
                <div className="mb-4 inline-flex h-10 w-10 items-center justify-center rounded-xl bg-accent/15 text-accent-light">
                  <Icon />
                </div>
                <h3 className="text-lg font-semibold">{f.title}</h3>
                <p className="mt-2 text-sm text-ink-400">{f.desc}</p>
              </motion.div>
            );
          })}
        </div>
      </div>
    </section>
  );
}

function GestureIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M5 11V7a2 2 0 014 0v4M9 11V5a2 2 0 014 0v6M13 11V6a2 2 0 014 0v8M17 14v-2a2 2 0 114 0v4a6 6 0 01-6 6H9a6 6 0 01-6-6v-1l2-3" />
    </svg>
  );
}
function KeyboardIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <rect x="2" y="6" width="20" height="12" rx="2" />
      <path d="M6 10h.01M10 10h.01M14 10h.01M18 10h.01M7 14h10" />
    </svg>
  );
}
function PresenterIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <rect x="3" y="4" width="18" height="12" rx="2" />
      <path d="M12 16v4M8 20h8" />
      <circle cx="15" cy="10" r="2" fill="currentColor" />
    </svg>
  );
}
function NetworkIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M5 13a10 10 0 0114 0M8.5 16.5a5 5 0 017 0" />
      <circle cx="12" cy="20" r="1" fill="currentColor" />
    </svg>
  );
}
function LockIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <rect x="4" y="10" width="16" height="11" rx="2" />
      <path d="M8 10V7a4 4 0 018 0v3" />
    </svg>
  );
}
function CodeIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M8 8l-4 4 4 4M16 8l4 4-4 4M14 4l-4 16" />
    </svg>
  );
}
