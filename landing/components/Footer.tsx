import Link from 'next/link';
import { site } from '@/lib/site';

export function Footer() {
  return (
    <footer className="border-t border-ink-800/60 bg-ink-900">
      <div className="container-tight flex flex-col gap-6 py-10 text-sm text-ink-400 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <div className="font-semibold text-ink-100">{site.name}</div>
          <div className="mt-1 text-xs">© {new Date().getFullYear()} · MIT License</div>
        </div>
        <nav className="flex flex-wrap items-center gap-5">
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
          <a href={`mailto:${site.contact}`} className="hover:text-ink-100">
            {site.contact}
          </a>
        </nav>
      </div>
    </footer>
  );
}
