import { SetupGuide } from '@/components/SetupGuide';
import { site } from '@/lib/site';

export const metadata = {
  title: `Установка — ${site.name}`,
};

export default function SetupPage() {
  return (
    <>
      <section className="relative pt-20 sm:pt-28">
        <div className="container-tight max-w-3xl">
          <h1 className="text-4xl font-bold tracking-tight">Установка</h1>
          <p className="mt-4 text-ink-400">
            Полная инструкция на случай, если что-то пошло не так.
          </p>
        </div>
      </section>
      <SetupGuide />

      <section className="py-16">
        <div className="container-tight max-w-3xl">
          <h2 className="text-2xl font-semibold">Если Mac ругается на безопасность</h2>
          <div className="surface mt-4 p-6 text-sm text-ink-400">
            <p>
              При первом запуске macOS может показать:
              <span className="ml-1 font-mono text-accent-light">
                «Padly cannot be opened because the developer cannot be verified»
              </span>
            </p>
            <p className="mt-4">Решение:</p>
            <ol className="mt-2 list-decimal space-y-1 pl-5">
              <li>
                Открой <span className="text-ink-100">System Settings → Privacy &amp; Security</span>.
              </li>
              <li>
                Внизу будет надпись «Padly was blocked». Нажми{' '}
                <span className="text-ink-100">Open Anyway</span>.
              </li>
              <li>
                Подтверди ещё раз в всплывающем окне — приложение запустится и
                больше спрашивать не будет.
              </li>
            </ol>
          </div>

          <h2 className="mt-12 text-2xl font-semibold">Если Android не даёт ставить APK</h2>
          <div className="surface mt-4 p-6 text-sm text-ink-400">
            <ol className="list-decimal space-y-1 pl-5">
              <li>
                <span className="text-ink-100">Настройки → Приложения → Спец-доступ → Установка неизвестных приложений</span>.
              </li>
              <li>
                Найди браузер или файловый менеджер, откуда открываешь APK.
              </li>
              <li>Поставь галку «Разрешить из этого источника».</li>
              <li>Открой APK ещё раз — установится.</li>
            </ol>
          </div>

          <h2 className="mt-12 text-2xl font-semibold">Pairing через QR</h2>
          <div className="surface mt-4 p-6 text-sm text-ink-400">
            <ol className="list-decimal space-y-1 pl-5">
              <li>На Mac кликни иконку Padly в menubar.</li>
              <li>Выбери <span className="text-ink-100">Show pairing QR</span>.</li>
              <li>На телефоне открой {site.name} → <span className="text-ink-100">Pair new Mac</span> → <span className="text-ink-100">Scan QR</span>.</li>
              <li>Mac покажет 6-значный PIN. Введи его на телефоне.</li>
              <li>Готово — трекпад работает.</li>
            </ol>
          </div>

          <h2 className="mt-12 text-2xl font-semibold">Pairing через одну сеть (без QR)</h2>
          <div className="surface mt-4 p-6 text-sm text-ink-400">
            <p>
              Если Mac и телефон в одном WiFi — pairing проще: открой Padly на
              телефоне, выбери <span className="text-ink-100">Find Mac on this network</span>, тапни по своему
              устройству в списке, введи PIN с Mac. Готово.
            </p>
          </div>

          <h2 className="mt-12 text-2xl font-semibold">Bluetooth-режим</h2>
          <div className="surface mt-4 p-6 text-sm text-ink-400">
            <p>
              На случай, когда сети нет: на телефоне открой Padly → <span className="text-ink-100">Settings → Bluetooth HID</span> → <span className="text-ink-100">Enable</span>. Mac
              увидит телефон как обычную BT-мышь — добавь его через стандартный
              Bluetooth-pairing macOS. Работают только базовые фичи: курсор,
              клик, скролл, клавиатура.
            </p>
          </div>
        </div>
      </section>
    </>
  );
}
