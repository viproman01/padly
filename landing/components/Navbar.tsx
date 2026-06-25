import Link from 'next/link';
import { site } from '@/lib/site';

export function Navbar() {
  return (
    <header className="sticky top-0 z-40 border-b border-ink-800/60 bg-ink-900/80 backdrop-blur">
      <div className="container-tight flex h-16 items-center justify-between">
        <Link href="/" className="flex items-center gap-2 font-bold tracking-tight">
          <Logo />
          <span>{site.name}</span>
        </Link>
        <nav className="hidden items-center gap-7 text-sm text-ink-400 sm:flex">
          <Link href="/#features" className="hover:text-ink-100">
            Фичи
          </Link>
          <Link href={site.links.setup} className="hover:text-ink-100">
            Установка
          </Link>
          <Link href={site.links.faq} className="hover:text-ink-100">
            FAQ
          </Link>
          <a
            href={site.links.github}
            target="_blank"
            rel="noreferrer noopener"
            className="hover:text-ink-100"
          >
            GitHub
          </a>
        </nav>
        <Link href={site.links.download} className="btn-primary !px-4 !py-2 text-xs">
          Скачать
        </Link>
      </div>
    </header>
  );
}

function Logo() {
  return (
    <svg width="22" height="22" viewBox="0 0 32 32" fill="none" aria-hidden>
      <rect x="2" y="2" width="28" height="28" rx="8" fill="url(#padly-grad)" />
      <rect x="9" y="9" width="14" height="14" rx="4" fill="#0e0e11" />
      <circle cx="16" cy="16" r="3" fill="#7c5cff" />
      <defs>
        <linearGradient id="padly-grad" x1="0" y1="0" x2="32" y2="32">
          <stop offset="0%" stopColor="#a895ff" />
          <stop offset="100%" stopColor="#5b3edc" />
        </linearGradient>
      </defs>
    </svg>
  );
}
