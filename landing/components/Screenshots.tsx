'use client';

import { motion } from 'framer-motion';

const shots = [
  {
    title: 'Трекпад-режим',
    body: 'Полноэкранный канвас. Один палец двигает курсор, два — скролл, три — листает Spaces, четыре — Mission Control.',
    visual: <TrackpadShot />,
  },
  {
    title: 'Клавиатура с модификаторами',
    body: 'Стандартная Android-клавиатура + ряд модификаторов сверху. Зажми cmd, тапни C — копирование, как на Mac.',
    visual: <KeyboardShot />,
  },
  {
    title: 'Презентер-режим',
    body: 'Стрелки слайдов, виртуальный лазер по тапу, таймер и тёмный экран. Работает в Keynote, PowerPoint, Google Slides.',
    visual: <PresenterShot />,
  },
];

export function Screenshots() {
  return (
    <section className="relative bg-ink-800/30 py-24">
      <div className="container-tight">
        <div className="mx-auto max-w-2xl text-center">
          <h2 className="text-3xl font-bold tracking-tight sm:text-4xl">
            Три режима — один свайп
          </h2>
          <p className="mt-3 text-ink-400">
            Переключайся между трекпадом, клавиатурой и презентером свайпом по
            нижнему краю.
          </p>
        </div>

        <div className="mt-14 grid gap-8 md:grid-cols-3">
          {shots.map((s, i) => (
            <motion.div
              key={s.title}
              initial={{ opacity: 0, y: 16 }}
              whileInView={{ opacity: 1, y: 0 }}
              viewport={{ once: true, margin: '-50px' }}
              transition={{ duration: 0.45, delay: i * 0.07 }}
              className="surface flex flex-col overflow-hidden"
            >
              <div className="bg-ink-900/60 p-6">{s.visual}</div>
              <div className="border-t border-ink-600/40 p-5">
                <h3 className="text-base font-semibold">{s.title}</h3>
                <p className="mt-1 text-sm text-ink-400">{s.body}</p>
              </div>
            </motion.div>
          ))}
        </div>
      </div>
    </section>
  );
}

function PhoneFrame({ children }: { children: React.ReactNode }) {
  return (
    <div className="mx-auto w-full max-w-[200px] overflow-hidden rounded-[2rem] border-4 border-ink-600/60 bg-ink-900 p-2">
      <div className="aspect-[9/19] overflow-hidden rounded-[1.5rem] bg-ink-800/40">
        {children}
      </div>
    </div>
  );
}

function TrackpadShot() {
  return (
    <PhoneFrame>
      <div className="flex h-full flex-col p-3">
        <div className="flex items-center justify-between text-[10px] text-ink-400">
          <span>Trackpad</span>
          <span>WiFi</span>
        </div>
        <div className="relative mt-2 flex-1 rounded-lg border border-accent/30 bg-accent/5">
          <div className="absolute left-1/3 top-1/3 h-3 w-3 rounded-full bg-accent/80" />
          <div className="absolute left-1/3 top-1/3 h-3 w-3 animate-ping rounded-full bg-accent/40" />
        </div>
        <div className="mt-2 flex justify-around text-[9px] text-ink-400">
          <span>1f · cursor</span>
          <span>2f · scroll</span>
          <span>3f · spaces</span>
        </div>
      </div>
    </PhoneFrame>
  );
}

function KeyboardShot() {
  return (
    <PhoneFrame>
      <div className="flex h-full flex-col p-3">
        <div className="flex items-center justify-between text-[10px] text-ink-400">
          <span>Keyboard</span>
          <span>● Connected</span>
        </div>
        <div className="mt-2 flex flex-1 flex-col gap-1.5">
          <div className="flex gap-1">
            {['cmd', 'opt', 'ctrl', 'shift'].map((m) => (
              <span
                key={m}
                className="flex-1 rounded border border-accent/40 bg-accent/10 px-1 py-1 text-center text-[9px] text-accent-light"
              >
                {m}
              </span>
            ))}
          </div>
          <div className="flex-1 rounded-lg border border-ink-600/40 bg-ink-900/60 p-2 text-[10px] text-ink-400">
            <div className="mb-1 text-ink-100">Type here…</div>
            <div className="text-accent-light">⌘ + C → copied</div>
          </div>
          <div className="grid grid-cols-10 gap-0.5 text-[8px] text-ink-400">
            {Array.from({ length: 30 }).map((_, i) => (
              <span
                key={i}
                className="aspect-square rounded-sm bg-ink-700/50"
              />
            ))}
          </div>
        </div>
      </div>
    </PhoneFrame>
  );
}

function PresenterShot() {
  return (
    <PhoneFrame>
      <div className="flex h-full flex-col p-3">
        <div className="flex items-center justify-between text-[10px] text-ink-400">
          <span>Presenter</span>
          <span>12:34</span>
        </div>
        <div className="mt-2 flex flex-1 flex-col rounded-lg border border-accent/30 bg-accent/5 p-2">
          <div className="text-center text-[9px] text-ink-400">Slide 7 / 22</div>
          <div className="mt-2 flex flex-1 items-center justify-between">
            <button className="rounded-full border border-ink-600/40 bg-ink-800/60 px-2 py-1 text-[10px] text-ink-400">
              ←
            </button>
            <div className="h-2 w-2 rounded-full bg-red-400 shadow-[0_0_12px_rgba(248,113,113,0.7)]" />
            <button className="rounded-full border border-ink-600/40 bg-ink-800/60 px-2 py-1 text-[10px] text-ink-400">
              →
            </button>
          </div>
          <div className="mt-2 text-center text-[9px] text-ink-400">
            tap = laser
          </div>
        </div>
      </div>
    </PhoneFrame>
  );
}
