export const site = {
  name: 'Padly',
  tagline: 'Твой Android как трекпад Mac',
  subline:
    'Мульти-тач жесты, полная клавиатура, презентер. Локальное соединение через WiFi или Bluetooth — без облака.',
  version: {
    android: '0.1.0',
    mac: '0.1.0',
  },
  downloads: {
    android: 'https://github.com/viproman01/padly/releases/latest/download/padly-android.apk',
    mac: 'https://github.com/viproman01/padly/releases/latest/download/Padly.dmg',
  },
  links: {
    github: 'https://github.com/viproman01/padly',
    releases: 'https://github.com/viproman01/padly/releases',
    setup: '/setup',
    faq: '/faq',
    download: '/download',
  },
  contact: 'hi@padly.app',
} as const;
