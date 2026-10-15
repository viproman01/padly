export const site = {
  name: 'Padly',
  // Адрес сайта для абсолютных ссылок (OG-картинка). Статика на VPS; другой адрес — через NEXT_PUBLIC_SITE_URL.
  url: process.env.NEXT_PUBLIC_SITE_URL ?? 'https://padly.109-172-47-162.sslip.io',
  tagline: 'Твой Android как трекпад Mac',
  subline:
    'Мульти-тач жесты, полная клавиатура, презентер. Локальное соединение через WiFi или Bluetooth — без облака.',
  version: {
    android: '0.1.0',
    mac: '0.1.0',
  },
  // Первый релиз ещё не опубликован: GitHub Releases пуст, ссылки ниже отдают 404.
  // Пока false — вместо «Скачать» кнопки «скоро» и ссылка на исходники.
  // После публикации релиза (тег v0.1.0 → workflow release.yml) поставить true и пересобрать сайт.
  releasesReady: false,
  downloads: {
    android: 'https://github.com/viproman01/padly/releases/latest/download/padly-android.apk',
    mac: 'https://github.com/viproman01/padly/releases/latest/download/Padly.dmg',
  },
  links: {
    github: 'https://github.com/viproman01/padly',
    releases: 'https://github.com/viproman01/padly/releases',
    issues: 'https://github.com/viproman01/padly/issues',
    setup: '/setup',
    faq: '/faq',
    download: '/download',
  },
} as const;
