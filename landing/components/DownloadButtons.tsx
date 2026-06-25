import { site } from '@/lib/site';

export function DownloadButtons() {
  return (
    <div className="flex flex-col gap-3 sm:flex-row">
      <a href={site.downloads.android} download className="btn-primary">
        <AndroidIcon />
        Скачать APK для Android
      </a>
      <a href={site.downloads.mac} download className="btn-ghost">
        <AppleIcon />
        Скачать .dmg для Mac
      </a>
    </div>
  );
}

function AndroidIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden>
      <path d="M17.523 15.341l1.387-2.4a.288.288 0 10-.499-.288l-1.405 2.434a8.59 8.59 0 00-3.506-.736c-1.247 0-2.42.267-3.506.736L8.59 12.653a.288.288 0 10-.499.288l1.387 2.4C7.054 16.605 5.473 18.93 5.18 21.7h13.638c-.293-2.77-1.874-5.095-4.295-6.359zM9.323 19.5a.728.728 0 110-1.456.728.728 0 010 1.456zm5.354 0a.728.728 0 110-1.456.728.728 0 010 1.456z" />
    </svg>
  );
}
function AppleIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden>
      <path d="M16.365 1.43c0 1.14-.482 2.27-1.272 3.092-.84.886-2.215 1.574-3.32 1.487-.13-1.121.43-2.297 1.23-3.12.84-.87 2.25-1.515 3.362-1.46zm4.18 16.95c-.71 1.493-1.052 2.16-1.967 3.482-1.275 1.84-3.073 4.135-5.305 4.158-1.98.022-2.494-1.262-5.18-1.247-2.685.015-3.252 1.27-5.233 1.247-2.234-.02-3.94-2.08-5.215-3.92C-4.61 18.93-5.038 13.058-2.515 10.04c1.793-2.142 4.62-3.395 7.275-3.395 2.703 0 4.404 1.475 6.638 1.475 2.165 0 3.486-1.478 6.611-1.478 2.36 0 4.86 1.282 6.643 3.502-5.83 3.193-4.886 11.508-1.107 14.236z" />
    </svg>
  );
}
