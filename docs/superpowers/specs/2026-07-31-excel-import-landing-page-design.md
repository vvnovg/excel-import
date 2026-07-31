# Лендинг excel-import: спецификация

**Дата:** 2026-07-31
**Статус:** утверждено к реализации

## 1. Назначение

Двуязычный (EN/RU) одностраничный лендинг для open-source библиотеки `excel-import`,
размещаемый на Cloudflare Pages на домене `novgorodtsev.org`. Лендинг — это выжимка
существующего `README.md` (не его дубликат): даёт посетителю за 30 секунд понять, что
делает библиотека, как её поставить и как сделать первый запуск, со ссылками на
GitHub/документацию.

Стек выбран с прицелом на позднейшее добавление документации (Starlight на том же
Astro) без смены технологий.

### Что лендинг не делает

- Не размещает полную документацию (аннотации, конфигурация, §5–7 спеки) — это позже
  на `docs.novgorodtsev.org`.
- Не ведёт блог, не собирает подписки, не имеет поиска.
- Не поднимает SSR/сервер — чистая статика.

## 2. Решения

| Параметр | Значение | Обоснование |
|---|---|---|
| Стек | **Astro** (static output) | Лендинг сейчас, Starlight-доки позже без смены стека |
| Языки | EN (default, `/`) + RU (`/ru/`) | Глобальная OSS-аудитория + совпадение RU с README |
| i18n-роутинг | `prefixDefaultLocale: false` | EN на apex `/`, RU на `/ru/` |
| Репозиторий | отдельный (не папка в `excel-import`) | Домен персональный, чистое разделение Java/Node |
| Хостинг | Cloudflare Pages | Домен уже на Cloudflare DNS+DNSSEC, бесплатно, авто-TLS |
| UI-фреймворк | без него (HTML+CSS, ~20 строк vanilla JS) | Лендинг не требует тяжёлого UI |
| Тема | `prefers-color-scheme` (светлая/тёмная) | Без ручного переключателя |

## 3. Структура проекта

Отдельный репозиторий (рабочее имя `excel-import-site`), корень — Astro-проект:

```
excel-import-site/
├── astro.config.mjs          # site + i18n + sitemap
├── package.json              # astro, @astrojs/sitemap; без лишних зависимостей
├── src/
│   ├── pages/
│   │   ├── index.astro       # EN  →  /
│   │   └── ru/
│   │       └── index.astro   # RU  →  /ru
│   ├── components/
│   │   ├── Hero.astro
│   │   ├── FeatureGrid.astro
│   │   ├── HowItWorks.astro
│   │   ├── Install.astro
│   │   ├── Quickstart.astro
│   │   ├── LanguageSwitcher.astro
│   │   └── Footer.astro
│   ├── i18n/
│   │   ├── ui.ts             # строки интерфейса (CTA, подписи) — { en, ru }
│   │   └── content.ts        # тексты секций (hero, features...) — { en, ru }
│   ├── layouts/
│   │   └── Base.astro        # <head>, мета, hreflang, шапка, переключатель
│   └── styles/
│       └── global.css        # токены (цвета, отступы, радиусы), темы
├── public/
│   ├── favicon.svg
│   ├── og-image.png          # соц-превью (позже, заглушка на старте)
│   ├── _redirects           # www → apex (301)
│   └── _headers             # HSTS, X-Content-Type-Options, Referrer-Policy
└── README.md
```

### 3.1 i18n

```js
// astro.config.mjs
export default defineConfig({
  site: 'https://novgorodtsev.org',
  integrations: [sitemap()],
  i18n: {
    defaultLocale: 'en',
    locales: ['en', 'ru'],
    routing: { prefixDefaultLocale: false },
  },
});
```

- Текстовый контент секций хранится в `src/i18n/content.ts` как `{ en: {...}, ru: {...} }`.
  Компоненты принимают `locale` и берут строки из словаря.
- **Install/Quickstart-код (Java-сниппеты) одинаковы для обоих языков** —
  не переводятся. Источник — `README.md` репозитория `excel-import`.
- Версия библиотеки в сниппетах — одна переменная (`SITE_VERSION` в `astro.config` или
  `content.ts`), чтобы править в одном месте.
- `Base.astro` проставляет `<link rel="alternate" hreflang="en|ru|x-default">`,
  `<html lang>` по локали, canonical.

### 3.2 Переключатель языка

`LanguageSwitcher` в шапке: ссылки `EN | RU` на парные страницы с сохранением секции
(`#quickstart` и т.п.). Активная локаль подсвечивается. Чистые ссылки, без JS.

## 4. Контент секций (одна страница)

| Секция | Содержание |
|---|---|
| **Hero** | Имя `excel-import`, tagline EN: «Stream `.xlsx` into PostgreSQL — memory stays flat, errors come back color-coded.» CTA: *Get started* (→ GitHub README, пока нет доков), *View on GitHub*, *Maven Central* (плейсхолдер до публикации). |
| **Feature grid** | 6 карточек из README: SAX streaming (heap не зависит от числа строк); annotation mapping (`@ExcelColumn`/`@ExcelSheet`/`@TargetTable`); Jakarta Bean Validation; multi-row INSERT batching + recursive bisection; green/red Excel report; `ON CONFLICT` strategies + Spring Boot starter. |
| **How it works** | Короткий абзац про два прохода и компактную карту исходов (~1 МБ на млн строк); мини-диаграмма (inline SVG) поток 1 / между проходами / поток 2. |
| **Install** | Gradle (Kotlin DSL) + Maven сниппеты для `core` и `spring-boot-starter` (из README), copy-кнопки. |
| **Quickstart** | Минимизированный пример из README: класс `EmployeeRow` с аннотациями + `ImportConfig` + `ExcelImporter` + `ImportReport`. Подсветка синтаксиса через `astro-expressive-code` (или ручной Shiki — финализируется при реализации). |
| **Footer / links** | GitHub (issues, releases), лицензия, автор, ссылка-заглушка «Docs → docs.novgorodtsev.org» (неактивна до реализации доков). |

## 5. Стилизация

- CSS-токены в `global.css`: цвета, межстрочное, радиусы, отступы.
- Тема через `prefers-color-scheme` (light/dark). Без ручного переключателя.
- Шрифт: системный стек + один акцентный (Inter с Google Fonts или `system-ui`).
  Без внешних зависимостей кроме шрифта.
- Адаптив: одна колонка на мобильном, grid на десктопе.
- Минимальный JS: copy-кнопка на сниппетах (~20 строк).
- Доступность: контрасты WCAG AA, семантический HTML, `lang` по локали.

## 6. Деплой на Cloudflare Pages

1. Запушить репозиторий на GitHub.
2. Cloudflare Pages → **Connect to Git** → выбрать репо.
3. Build command: `npm run build`; output dir: `dist`.
4. Привязать custom domains `novgorodtsev.org` и `www.novgorodtsev.org`
   (Cloudflare создаст CNAME на apex через flattening + CNAME для www).
5. SSL/TLS = **Full (strict)**; Always Use HTTPS = ON.
6. `public/_redirects`: `https://www.novgorodtsev.org/*  https://novgorodtsev.org/:splat  301!`
7. `public/_headers`: HSTS, `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`.

Канонический домен — apex `novgorodtsev.org` (RU/EN живут на путях `/` и `/ru`).

## 7. Путь к документации (позже, вне этой спеки)

Позже добавляется **Starlight** (на том же Astro) — отдельный Pages-проект на
`docs.novgorodtsev.org` либо секция `/docs` текущего проекта. Лендинг оставляет
ссылку-заглушку; при реализации доков заглушка меняется на активную ссылку. Стек не
меняется — ради этого Astro и был выбран.

## 8. Что не входит (YAGNI)

Поиск, блог, комментарии, аналитика со скриптами, ручной переключатель темы, SSR,
формы, RSS. Любое из этого добавляется позже отдельным шагом.