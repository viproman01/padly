'use client';

import { motion } from 'framer-motion';
import { site } from '@/lib/site';
import { DownloadButtons } from './DownloadButtons';

export function Hero() {
  return (
    <section className="relative overflow-hidden">
      <div className="gradient-radial absolute inset-0 -z-10 opacity-90" aria-hidden />
      <div className="grid-overlay absolute inset-0 -z-10 opacity-60" aria-hidden />

      <div className="container-tight relative pb-24 pt-20 text-center sm:pt-32">
        <motion.div
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.5 }}
          className="inline-flex items-center gap-2 rounded-full border border-accent/30 bg-accent/10 px-3 py-1 text-xs font-medium text-accent-light"
        >
          <span className="h-1.5 w-1.5 rounded-full bg-accent animate-pulse-soft" />
          Open beta · v{site.version.android}
        </motion.div>

        <motion.h1
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6, delay: 0.05 }}
          className="mx-auto mt-6 max-w-3xl text-balance text-5xl font-bold leading-tight tracking-tight sm:text-6xl"
        >
          {site.tagline}
        </motion.h1>

        <motion.p
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6, delay: 0.1 }}
          className="mx-auto mt-6 max-w-2xl text-pretty text-lg text-ink-400"
        >
          {site.subline}
        </motion.p>

        <motion.div
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6, delay: 0.15 }}
          className="mt-10 flex justify-center"
        >
          <DownloadButtons />
        </motion.div>

        <motion.div
          initial={{ opacity: 0, y: 28 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.7, delay: 0.25 }}
          className="mx-auto mt-20 max-w-3xl"
        >
          <HeroMockup />
        </motion.div>
      </div>
    </section>
  );
}

function HeroMockup() {
  return (
    <div className="surface relative overflow-hidden p-4 shadow-soft">
      <div className="grid gap-4 sm:grid-cols-[1.7fr_1fr]">
        <MacMock />
        <PhoneMock />
      </div>
    </div>
  );
}

function MacMock() {
  return (
    <div className="relative overflow-hidden rounded-xl border border-ink-600/50 bg-ink-900">
      <div className="flex items-center gap-1.5 border-b border-ink-600/40 bg-ink-800/80 px-3 py-2">
        <span className="h-2.5 w-2.5 rounded-full bg-red-400/80" />
        <span className="h-2.5 w-2.5 rounded-full bg-yellow-400/80" />
        <span className="h-2.5 w-2.5 rounded-full bg-emerald-400/80" />
        <span className="ml-3 text-xs text-ink-400">menubar · Padly</span>
      </div>
      <div className="grid grid-cols-3 gap-2 p-4">
        {[
          'Mission Control',
          'Safari',
          'Keynote',
          'Terminal',
          'Mail',
          'Finder',
        ].map((label) => (
          <div
            key={label}
            className="aspect-[1.4] rounded-lg border border-ink-600/40 bg-ink-800/40 p-3 text-xs text-ink-400"
          >
            {label}
          </div>
        ))}
      </div>
      <div className="absolute bottom-3 left-1/2 -translate-x-1/2 rounded-full border border-accent/40 bg-accent/10 px-3 py-1 text-xs text-accent-light">
        ←  ctrl  +  →   space switch
      </div>
    </div>
  );
}

function PhoneMock() {
  return (
    <div className="relative mx-auto w-full max-w-[200px] overflow-hidden rounded-[2rem] border-4 border-ink-600/60 bg-ink-900 p-2">
      <div className="aspect-[9/19] rounded-[1.5rem] border border-ink-600/40 bg-ink-800/30 p-3">
        <div className="flex items-center justify-between text-[10px] text-ink-400">
          <span>16:24</span>
          <span>● Connected</span>
        </div>
        <div className="mt-3 flex h-[78%] flex-col rounded-lg border border-accent/30 bg-accent/5">
          <div className="flex-1" />
          <div className="border-t border-ink-600/40 px-3 py-2 text-[10px] text-ink-400">
            Trackpad · 3-4 finger gestures
          </div>
        </div>
        <div className="mt-2 flex justify-center gap-1.5">
          {['cmd', 'opt', 'ctrl', 'shift'].map((m) => (
            <span
              key={m}
              className="rounded border border-ink-600/40 bg-ink-800/40 px-1.5 py-0.5 text-[9px] text-ink-400"
            >
              {m}
            </span>
          ))}
        </div>
      </div>
    </div>
  );
}
