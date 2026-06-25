import { DownloadButtons } from '@/components/DownloadButtons';
import { site } from '@/lib/site';

export const metadata = {
  title: `Скачать — ${site.name}`,
};

export default function DownloadPage() {
  return (
    <section className="relative py-24">
      <div className="container-tight max-w-3xl">
        <h1 className="text-4xl font-bold tracking-tight">Скачать {site.name}</h1>
        <p className="mt-4 text-ink-400">
          Два прямых файла. Никакой регистрации, аккаунта или App Store.
        </p>

        <p className="mt-2 text-xs text-ink-500">
          Сборки публикуются автоматически в{' '}
          <a href={site.links.releases} className="underline">
            GitHub Releases
          </a>
          . Кнопки ниже всегда тянут последний релиз.
        </p>

        <div className="surface mt-10 p-8">
          <h2 className="text-xl font-semibold">Android</h2>
          <p className="mt-1 text-sm text-ink-400">
            Android 9+ · Подпись разработчика (release keystore)
          </p>
          <ol className="mt-4 list-decimal space-y-2 pl-5 text-sm text-ink-400">
            <li>Скачай APK по кнопке ниже.</li>
            <li>
              В Android разреши установку из неизвестных источников
              (Настройки → Безопасность → Спец-доступ → Установка приложений).
            </li>
            <li>Открой APK через файловый менеджер и установи.</li>
          </ol>
          <a
            href={site.downloads.android}
            rel="noopener"
            className="btn-primary mt-6 w-fit"
          >
            Скачать padly-android.apk
          </a>
        </div>

        <div className="surface mt-6 p-8">
          <h2 className="text-xl font-semibold">macOS</h2>
          <p className="mt-1 text-sm text-ink-400">
            macOS 14 (Sonoma) и новее · Apple Silicon и Intel · ad-hoc подпись
          </p>
          <ol className="mt-4 list-decimal space-y-2 pl-5 text-sm text-ink-400">
            <li>Скачай .dmg по кнопке ниже.</li>
            <li>Открой .dmg и перетащи Padly в Applications.</li>
            <li>
              Первый запуск: правый клик по Padly → «Открыть» → «Открыть»
              (Gatekeeper, потому что без Apple Developer ID).
            </li>
            <li>
              Дай разрешение Accessibility (нужно, чтобы инжектить курсор и
              клавиши).
            </li>
          </ol>
          <a href={site.downloads.mac} rel="noopener" className="btn-ghost mt-6 w-fit">
            Скачать Padly.dmg
          </a>
        </div>

        <div className="mt-12 text-center">
          <DownloadButtons />
        </div>
      </div>
    </section>
  );
}
