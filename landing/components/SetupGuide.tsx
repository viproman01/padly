'use client';

import { motion } from 'framer-motion';

const steps = [
  {
    n: 1,
    title: 'Поставь Mac-приложение',
    body: 'Открой .dmg, перетащи Padly в Applications. При первом запуске System Settings → Privacy & Security → Accessibility — поставь галку напротив Padly.',
  },
  {
    n: 2,
    title: 'Поставь Android-приложение',
    body: 'Скачай APK на телефон. Android спросит разрешение на установку из неизвестных источников — разреши. Открой Padly, дай доступ к камере (для QR) и Bluetooth.',
  },
  {
    n: 3,
    title: 'Свяжи устройства',
    body: 'На Mac кликни иконку в menubar → "Show pairing QR". Скан QR с телефона. Mac покажет 6-значный PIN — введи его на телефоне. Всё, готово.',
  },
];

export function SetupGuide() {
  return (
    <section id="setup" className="relative py-24">
      <div className="container-tight">
        <div className="mx-auto max-w-2xl text-center">
          <h2 className="text-3xl font-bold tracking-tight sm:text-4xl">
            Три шага до первого клика
          </h2>
          <p className="mt-3 text-ink-400">
            Занимает меньше двух минут. Серьёзно.
          </p>
        </div>

        <div className="mt-14 grid gap-6 md:grid-cols-3">
          {steps.map((s, i) => (
            <motion.div
              key={s.n}
              initial={{ opacity: 0, y: 16 }}
              whileInView={{ opacity: 1, y: 0 }}
              viewport={{ once: true, margin: '-50px' }}
              transition={{ duration: 0.45, delay: i * 0.07 }}
              className="surface relative p-6"
            >
              <div className="absolute -top-4 left-6 flex h-8 w-8 items-center justify-center rounded-full bg-accent text-sm font-bold text-white shadow-glow">
                {s.n}
              </div>
              <h3 className="mt-2 text-lg font-semibold">{s.title}</h3>
              <p className="mt-2 text-sm text-ink-400">{s.body}</p>
            </motion.div>
          ))}
        </div>
      </div>
    </section>
  );
}
