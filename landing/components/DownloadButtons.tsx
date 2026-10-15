import { site } from '@/lib/site';

export function DownloadButtons() {
  if (!site.releasesReady) {
    // Релиза ещё нет: вместо ссылок на пустой GitHub Releases — исходники и честное «скоро»
    return (
      <div className="flex flex-col gap-3 sm:flex-row">
        <a
          href={site.links.github}
          target="_blank"
          rel="noreferrer noopener"
          className="btn-primary"
        >
          <GitHubIcon />
          Исходники на GitHub
        </a>
        <span className="btn-soon" aria-disabled="true">
          APK и .dmg — скоро
        </span>
      </div>
    );
  }
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

function GitHubIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden>
      <path d="M12 .5C5.65.5.5 5.65.5 12a11.5 11.5 0 007.86 10.92c.58.1.79-.25.79-.56v-2c-3.2.7-3.87-1.37-3.87-1.37-.52-1.33-1.28-1.69-1.28-1.69-1.04-.71.08-.7.08-.7 1.15.08 1.76 1.19 1.76 1.19 1.03 1.76 2.69 1.25 3.35.96.1-.75.4-1.25.73-1.54-2.55-.29-5.24-1.28-5.24-5.68 0-1.26.45-2.28 1.19-3.09-.12-.29-.52-1.46.11-3.05 0 0 .97-.31 3.17 1.18a11 11 0 015.77 0c2.2-1.49 3.17-1.18 3.17-1.18.63 1.59.23 2.76.11 3.05.74.81 1.19 1.83 1.19 3.09 0 4.41-2.69 5.39-5.25 5.67.41.36.78 1.06.78 2.14v3.17c0 .31.21.67.8.56A11.5 11.5 0 0023.5 12C23.5 5.65 18.35.5 12 .5z" />
    </svg>
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
