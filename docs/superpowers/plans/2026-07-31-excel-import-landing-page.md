# excel-import Лендинг (EN/RU, Astro, Cloudflare Pages) — План реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Двуязычный одностраничный лендинг библиотеки `excel-import` на Astro, деплоящийся на Cloudflare Pages на домене `novgorodtsev.org`.

**Architecture:** Отдельный git-репозиторий `excel-import-site` (соседний с `excel-import`, по пути `../excel-import-site`). Astro static output со встроенным i18n (EN на `/`, RU на `/ru/`), sitemap, syntax-highlighting через `astro-expressive-code`. Текстовый контент в словарях `src/i18n/`. Каждая задача завершается прогоном smoke-теста (`scripts/smoke.mjs`), проверяющего, что собранный HTML содержит ожидаемые строки для каждой локали.

**Tech Stack:** Astro 5, `@astrojs/sitemap`, `astro-expressive-code`, Node 20+, чистый CSS (без UI-фреймворка), Cloudflare Pages.

## Global Constraints

- Отдельный репозиторий по абсолютному пути `/Users/vvnovg/projects/excel-import-site` (создаётся в Task 1).
- Канонический домен `https://novgorodtsev.org`; RU на `/ru/`, EN на `/`.
- Версия библиотеки в сниппетах — единая константа `SITE_VERSION = "0.1.0"` в `src/i18n/content.ts`.
- GitHub URL — `https://github.com/vvnovg/excel-import` (источник: `build.gradle.kts:63` репо `excel-import`; пользователь при необходимости правит).
- Кодировка UTF-8, `lang` по локали, контрасты WCAG AA, `prefers-color-scheme` для тёмной/светлой темы.
- Никакого SSR, никакого UI-фреймворка, никаких аналитик-скриптов.
- Каждый коммит — в репозитории `excel-import-site` (не в `excel-import`).

## File Structure

| Файл | Ответственность |
|---|---|
| `astro.config.mjs` | site URL, i18n, integrations (sitemap, expressive-code) |
| `package.json` | зависимости, scripts (build/smoke/check) |
| `scripts/smoke.mjs` | smoke-тест: проверка содержимого `dist/**/*.html` |
| `src/i18n/ui.ts` | строки интерфейса (CTA, подписи) — `{ en, ru }` |
| `src/i18n/content.ts` | тексты секций + `SITE_VERSION` + `GITHUB_URL` — `{ en, ru }` |
| `src/layouts/Base.astro` | `<head>`, мета, hreflang, шапка с переключателем, shell |
| `src/components/LanguageSwitcher.astro` | ссылки EN/RU с подсветкой активной |
| `src/components/Hero.astro` | hero (tagline + CTA) |
| `src/components/FeatureGrid.astro` | 6 карточек возможностей |
| `src/components/HowItWorks.astro` | абзац + inline SVG диаграмма |
| `src/components/Install.astro` | Gradle/Maven сниппеты (expressive-code) |
| `src/components/Quickstart.astro` | Java-пример (expressive-code) |
| `src/components/Footer.astro` | ссылки, лицензия, автор, заглушка доков |
| `src/pages/index.astro` | EN-страница (собирает секции) |
| `src/pages/ru/index.astro` | RU-страница |
| `src/styles/global.css` | CSS-токены, темы, базовые стили |
| `public/favicon.svg` | иконка |
| `public/_redirects` | www → apex (301) |
| `public/_headers` | security headers |
| `public/robots.txt` | allow all + sitemap |
| `README.md` | как запускать/деплоить |
| `.gitignore` | node_modules, dist, .astro |

---

### Task 1: Scaffold Astro project + config + smoke harness

**Files:**
- Create: `/Users/vvnovg/projects/excel-import-site/package.json`
- Create: `/Users/vvnovg/projects/excel-import-site/astro.config.mjs`
- Create: `/Users/vvnovg/projects/excel-import-site/scripts/smoke.mjs`
- Create: `/Users/vvnovg/projects/excel-import-site/.gitignore`
- Create: `/Users/vvnovg/projects/excel-import-site/src/pages/index.astro` (placeholder)
- Create: `/Users/vvnovg/projects/excel-import-site/src/pages/ru/index.astro` (placeholder)

**Interfaces:**
- Produces: рабочий `npm run build` → `dist/index.html` и `dist/ru/index.html`; `npm run smoke` (выход 1, пока контента нет); git-репо `excel-import-site`.

- [ ] **Step 1: Создать каталог и инициализировать git**

Run:
```bash
mkdir -p /Users/vvnovg/projects/excel-import-site
cd /Users/vvnovg/projects/excel-import-site
git init
git branch -M main
```

- [ ] **Step 2: Установить зависимости**

Run:
```bash
cd /Users/vvnovg/projects/excel-import-site
npm init -y
npm install astro@^5 @astrojs/sitemap@^3 astro-expressive-code@^0.38
```
Ожидается: создан `package.json`, установлен `node_modules`.

- [ ] **Step 3: Создать `.gitignore`**

```gitignore
node_modules/
dist/
.astro/
.DS_Store
```

- [ ] **Step 4: Создать `astro.config.mjs`**

```js
import { defineConfig } from 'astro/config';
import sitemap from '@astrojs/sitemap';
import expressiveCode from 'astro-expressive-code';

export default defineConfig({
  site: 'https://novgorodtsev.org',
  integrations: [expressiveCode(), sitemap()],
  i18n: {
    defaultLocale: 'en',
    locales: ['en', 'ru'],
    routing: { prefixDefaultLocale: false },
  },
});
```

- [ ] **Step 5: Дописать scripts в `package.json`**

Привести `package.json` к виду (вручную или через `npm pkg set`):
```json
{
  "name": "excel-import-site",
  "version": "0.1.0",
  "type": "module",
  "private": true,
  "scripts": {
    "dev": "astro dev",
    "build": "astro build",
    "preview": "astro preview",
    "check": "astro check",
    "smoke": "node scripts/smoke.mjs"
  },
  "dependencies": {
    "astro": "^5.0.0",
    "@astrojs/sitemap": "^3.0.0",
    "astro-expressive-code": "^0.38.0"
  }
}
```
(Версии оставить те, что поставил npm — важно наличие `"type": "module"` и блока `scripts`.)

- [ ] **Step 6: Создать placeholder-страницы**

`src/pages/index.astro`:
```astro
---
---
<html lang="en"><body><h1>excel-import</h1></body></html>
```

`src/pages/ru/index.astro`:
```astro
---
---
<html lang="ru"><body><h1>excel-import</h1></body></html>
```

- [ ] **Step 7: Создать smoke-тест `scripts/smoke.mjs`**

```js
import { readFileSync, existsSync } from 'node:fs';

let fail = 0;
const checks = [
  { path: 'dist/index.html', must: ['<html lang="en">', 'excel-import'] },
  { path: 'dist/ru/index.html', must: ['<html lang="ru">', 'excel-import'] },
];
for (const c of checks) {
  if (!existsSync(c.path)) { console.error(`MISSING FILE ${c.path}`); fail++; continue; }
  const html = readFileSync(c.path, 'utf8');
  for (const s of c.must) {
    if (!html.includes(s)) { console.error(`MISSING "${s}" in ${c.path}`); fail++; }
  }
}
if (fail) { console.error(`\n${fail} smoke check(s) failed`); process.exit(1); }
console.log('smoke OK');
```

- [ ] **Step 8: Build + smoke (должен пройти)**

Run:
```bash
cd /Users/vvnovg/projects/excel-import-site
npm run build
npm run smoke
```
Ожидается: `smoke OK`.

- [ ] **Step 9: Commit**

```bash
cd /Users/vvnovg/projects/excel-import-site
git add -A
git commit -m "feat: scaffold astro project with i18n, sitemap, smoke test"
```

---

### Task 2: Base layout, global.css, LanguageSwitcher, page shells

**Files:**
- Create: `src/styles/global.css`
- Create: `src/layouts/Base.astro`
- Create: `src/components/LanguageSwitcher.astro`
- Modify: `src/pages/index.astro`, `src/pages/ru/index.astro`
- Modify: `scripts/smoke.mjs`

**Interfaces:**
- Produces: `Base.astro` принимает props `{ locale: 'en'|'ru' }` и slot; рендерит шапку с `LanguageSwitcher`, `<html lang>`, hreflang. Обе страницы используют `Base`.

- [ ] **Step 1: Расширить smoke-тест новыми ожиданиями**

Заменить массив `checks` в `scripts/smoke.mjs`:
```js
const checks = [
  { path: 'dist/index.html', must: ['<html lang="en">', 'hreflang="ru"', 'hreflang="en"', 'LanguageSwitcher', 'excel-import'] },
  { path: 'dist/ru/index.html', must: ['<html lang="ru">', 'hreflang="ru"', 'hreflang="en"', 'excel-import'] },
];
```

- [ ] **Step 2: Build + smoke (должен упасть — нет hreflang)**

Run: `npm run build && npm run smoke`
Ожидается: FAIL с `MISSING "hreflang=ru" in dist/index.html`.

- [ ] **Step 3: Создать `src/styles/global.css`**

```css
:root {
  --bg: #ffffff; --fg: #1a1a1a; --muted: #5a5a5a; --accent: #2563eb;
  --card-bg: #f7f7f8; --border: #e2e2e4;
  --maxw: 960px; --gap: 1.25rem; --radius: 10px;
  --font: system-ui, -apple-system, "Segoe UI", Roboto, Arial, sans-serif;
  --mono: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
@media (prefers-color-scheme: dark) {
  :root { --bg:#0f1115; --fg:#e7e9ee; --muted:#a3a8b4; --accent:#6ea8fe;
          --card-bg:#171a20; --border:#262b33; }
}
* { box-sizing: border-box; }
html { scroll-behavior: smooth; }
body { margin:0; background:var(--bg); color:var(--fg); font-family:var(--font);
       line-height:1.6; -webkit-font-smoothing:antialiased; }
a { color:var(--accent); }
.wrap { max-width:var(--maxw); margin:0 auto; padding:0 1.25rem; }
header.site { border-bottom:1px solid var(--border); }
header.site .wrap { display:flex; justify-content:space-between; align-items:center; padding:0.75rem 1.25rem; }
.brand { font-weight:700; color:var(--fg); text-decoration:none; }
.switch a + a { margin-left:0.5rem; }
.switch a[aria-current="true"] { font-weight:700; color:var(--fg); }
section { padding:3rem 0; border-bottom:1px solid var(--border); }
h1 { font-size:clamp(1.8rem,5vw,2.6rem); line-height:1.15; }
h2 { font-size:clamp(1.4rem,3vw,1.9rem); margin-top:0; }
p { color:var(--muted); }
code { font-family:var(--mono); }
```

- [ ] **Step 4: Создать `src/components/LanguageSwitcher.astro`**

```astro
---
const { locale } = Astro.props;
const links = [
  { code: 'en', href: '/', label: 'EN' },
  { code: 'ru', href: '/ru/', label: 'RU' },
];
---
<nav class="switch" aria-label="Language">
  {links.map(l => (
    <a href={l.href} aria-current={l.code === locale ? 'true' : 'false'}>{l.label}</a>
  ))}
</nav>
```

- [ ] **Step 5: Создать `src/layouts/Base.astro`**

```astro
---
import '../styles/global.css';
import LanguageSwitcher from '../components/LanguageSwitcher.astro';
const { locale } = Astro.props;
const other = locale === 'en' ? { href: '/ru/', hreflang: 'ru' } : { href: '/', hreflang: 'en' };
const self = locale === 'en' ? { href: '/', hreflang: 'en' } : { href: '/ru/', hreflang: 'ru' };
const title = locale === 'en' ? 'excel-import — stream .xlsx into PostgreSQL' : 'excel-import — потоковый импорт .xlsx в PostgreSQL';
---
<!doctype html>
<html lang={locale}>
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>{title}</title>
    <meta name="description" content={title} />
    <link rel="icon" type="image/svg+xml" href="/favicon.svg" />
    <link rel="alternate" hreflang={self.hreflang} href={`https://novgorodtsev.org${self.href}`} />
    <link rel="alternate" hreflang={other.hreflang} href={`https://novgorodtsev.org${other.href}`} />
    <link rel="alternate" hreflang="x-default" href="https://novgorodtsev.org/" />
  </head>
  <body>
    <header class="site">
      <div class="wrap">
        <a class="brand" href={self.href}>excel-import</a>
        <LanguageSwitcher locale={locale} />
      </div>
    </header>
    <main><slot /></main>
  </body>
</html>
```

- [ ] **Step 6: Переписать страницы через `Base`**

`src/pages/index.astro`:
```astro
---
import Base from '../layouts/Base.astro';
---
<Base locale="en">
  <div class="wrap"><p>placeholder</p></div>
</Base>
```

`src/pages/ru/index.astro`:
```astro
---
import Base from '../../layouts/Base.astro';
---
<Base locale="ru">
  <div class="wrap"><p>заглушка</p></div>
</Base>
```

- [ ] **Step 7: Build + smoke (должен пройти)**

Run: `npm run build && npm run smoke`
Ожидается: `smoke OK`.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: base layout, theme tokens, language switcher, page shells"
```

---

### Task 3: i18n dictionaries (ui + content)

**Files:**
- Create: `src/i18n/ui.ts`
- Create: `src/i18n/content.ts`
- Modify: `scripts/smoke.mjs`

**Interfaces:**
- Produces: `ui` — `{ en: {...}, ru: {...} }` со строками CTA/подписей; `content` — `{ version, githubUrl, en: {...}, ru: {...} }` со всеми текстами секций. Используется всеми компонентами далее.

- [ ] **Step 1: Расширить smoke — проверить ключевые строки обеих локалей**

Заменить `checks`:
```js
const checks = [
  { path: 'dist/index.html', must: ['Stream `.xlsx` into PostgreSQL', 'View on GitHub', 'Get started'] },
  { path: 'dist/ru/index.html', must: ['Потоковый импорт', 'GitHub', 'Начать'] },
];
```

- [ ] **Step 2: Build + smoke (должен упасть)**

Run: `npm run build && npm run smoke`
Ожидается: FAIL (`MISSING "Stream ..."`).

- [ ] **Step 3: Создать `src/i18n/ui.ts`**

```ts
export const ui = {
  en: {
    start: 'Get started',
    github: 'View on GitHub',
    maven: 'Maven Central',
  },
  ru: {
    start: 'Начать',
    github: 'Открыть на GitHub',
    maven: 'Maven Central',
  },
} as const;
```

- [ ] **Step 4: Создать `src/i18n/content.ts`**

```ts
export const SITE_VERSION = '0.1.0';
export const GITHUB_URL = 'https://github.com/vvnovg/excel-import';

export const content = {
  version: SITE_VERSION,
  githubUrl: GITHUB_URL,
  en: {
    hero: {
      tagline: 'Stream `.xlsx` into PostgreSQL — memory stays flat, errors come back color-coded.',
    },
    features: {
      heading: 'Features',
      items: [
        { title: 'Streaming SAX reader', body: 'Heap usage is independent of row count — import files that don’t fit in memory.' },
        { title: 'Annotation mapping', body: 'Map rows to POJOs with @ExcelSheet / @ExcelColumn / @TargetTable.' },
        { title: 'Jakarta Bean Validation', body: 'Per-row validation with @NotNull, @Size, @PastOrPresent and more.' },
        { title: 'Multi-row INSERT batching', body: 'One INSERT per batch, one transaction per batch, recursive bisection on failure.' },
        { title: 'Color-coded Excel report', body: 'Inserted rows green, rejected rows red, reason in an added column.' },
        { title: 'ON CONFLICT + Spring Boot starter', body: 'Conflict strategies (doNothing/doUpdate) and an optional Spring Boot autoconfigure.' },
      ],
    },
    how: {
      heading: 'How it works',
      body: 'The file is read twice. Pass 1 streams data through mapping, validation and batched insert; between passes only a compact outcome map lives in memory (~1 MB per million rows; messages spill to a temp file). Pass 2 re-reads the file and writes a color-coded report via SXSSF.',
    },
    install: { heading: 'Install', gradle: 'Gradle (Kotlin DSL)', maven: 'Maven' },
    quickstart: { heading: 'Quickstart' },
    footer: {
      github: 'GitHub',
      issues: 'Issues',
      license: 'License: MIT',
      author: 'Viacheslav Novgorodtsev',
      docs: 'Docs — coming soon at docs.novgorodtsev.org',
    },
  },
  ru: {
    hero: {
      tagline: 'Потоковый импорт `.xlsx` в PostgreSQL — память не растёт от числа строк, ошибки возвращаются с цветовой разметкой.',
    },
    features: {
      heading: 'Возможности',
      items: [
        { title: 'Потоковое SAX-чтение', body: 'Потребление heap не зависит от числа строк — импортируются файлы, не помещающиеся в память.' },
        { title: 'Маппинг по аннотациям', body: 'Строки на POJO через @ExcelSheet / @ExcelColumn / @TargetTable.' },
        { title: 'Jakarta Bean Validation', body: 'Построчная валидация: @NotNull, @Size, @PastOrPresent и др.' },
        { title: 'Батчинг multi-row INSERT', body: 'Один INSERT на батч, одна транзакция на батч, рекурсивная бисекция при сбое.' },
        { title: 'Excel-отчёт с разметкой', body: 'Вставленные строки зелёные, отклонённые красные, причина — в добавленной колонке.' },
        { title: 'ON CONFLICT + Spring Boot starter', body: 'Стратегии конфликтов (doNothing/doUpdate) и опциональная Spring Boot autoconfiguration.' },
      ],
    },
    how: {
      heading: 'Как это работает',
      body: 'Файл читается дважды. Проход 1 стримит данные через маппинг, валидацию и батч-вставку; между проходами в памяти живёт только компактная карта исходов (~1 МБ на миллион строк; сообщения вытесняются во временный файл). Проход 2 перечитывает файл и пишет цветной отчёт через SXSSF.',
    },
    install: { heading: 'Установка', gradle: 'Gradle (Kotlin DSL)', maven: 'Maven' },
    quickstart: { heading: 'Быстрый старт' },
    footer: {
      github: 'GitHub',
      issues: 'Задачи',
      license: 'Лицензия: MIT',
      author: 'Вячеслав Новгородцев',
      docs: 'Документация — скоро на docs.novgorodtsev.org',
    },
  },
} as const;
```

- [ ] **Step 5: Build + smoke (контента в страницах ещё нет → всё ещё падает)**

Run: `npm run build && npm run smoke`
Ожидается: FAIL (строки не вставлены в HTML). Это нормально — вставку делает Task 4+.

- [ ] **Step 6: Commit (словари готовы, компоненты — дальше)**

```bash
git add -A
git commit -m "feat: i18n ui + content dictionaries (en/ru)"
```

---

### Task 4: Hero section

**Files:**
- Create: `src/components/Hero.astro`
- Modify: `src/pages/index.astro`, `src/pages/ru/index.astro`
- Modify: `scripts/smoke.mjs`

**Interfaces:**
- Consumes: `content[locale].hero.tagline`, `ui[locale]`, `content.githubUrl`, `content.version`.
- Produces: `Hero` с props `{ locale }`, рендерит tagline + 3 CTA (start → `#quickstart`, github → `content.githubUrl`, maven → плейсхолдер `#`).

- [ ] **Step 1: Расширить smoke**

```js
const checks = [
  { path: 'dist/index.html', must: ['Stream `.xlsx` into PostgreSQL', 'View on GitHub', 'Get started', '0.1.0'] },
  { path: 'dist/ru/index.html', must: ['Потоковый импорт', 'Открыть на GitHub', 'Начать', '0.1.0'] },
];
```

- [ ] **Step 2: Build + smoke (падает)**

Run: `npm run build && npm run smoke` → FAIL.

- [ ] **Step 3: Создать `src/components/Hero.astro`**

```astro
---
import { content } from '../i18n/content';
import { ui } from '../i18n/ui';
const { locale } = Astro.props;
const t = content[locale];
const s = ui[locale];
---
<section class="hero">
  <div class="wrap">
    <h1>excel-import <span class="ver">v{content.version}</span></h1>
    <p class="lead">{t.hero.tagline}</p>
    <p class="cta">
      <a href="#quickstart">{s.start}</a>
      <a href={content.githubUrl} rel="noopener">{s.github}</a>
      <a href="#" aria-disabled="true">{s.maven}</a>
    </p>
  </div>
</section>
<style>
  .hero { padding:5rem 0 3rem; }
  .hero .ver { font-size:0.9rem; color:var(--muted); font-weight:400; }
  .lead { font-size:1.2rem; color:var(--muted); max-width:42rem; }
  .cta { display:flex; gap:0.75rem; flex-wrap:wrap; }
  .cta a { display:inline-block; padding:0.6rem 1.1rem; border:1px solid var(--border);
           border-radius:var(--radius); text-decoration:none; color:var(--fg); }
  .cta a:first-child { background:var(--accent); color:#fff; border-color:var(--accent); }
  .cta a[aria-disabled="true"] { opacity:0.6; pointer-events:none; }
</style>
```

- [ ] **Step 4: Вставить Hero в страницы**

`src/pages/index.astro`:
```astro
---
import Base from '../layouts/Base.astro';
import Hero from '../components/Hero.astro';
---
<Base locale="en"><Hero locale="en" /></Base>
```

`src/pages/ru/index.astro`:
```astro
---
import Base from '../../layouts/Base.astro';
import Hero from '../../components/Hero.astro';
---
<Base locale="ru"><Hero locale="ru" /></Base>
```

- [ ] **Step 5: Build + smoke (проходит)**

Run: `npm run build && npm run smoke` → `smoke OK`.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: hero section (tagline + CTA) en/ru"
```

---

### Task 5: Feature grid

**Files:**
- Create: `src/components/FeatureGrid.astro`
- Modify: `src/pages/index.astro`, `src/pages/ru/index.astro`
- Modify: `scripts/smoke.mjs`

- [ ] **Step 1: Расширить smoke**

```js
const checks = [
  { path: 'dist/index.html', must: ['Stream `.xlsx` into PostgreSQL', 'Streaming SAX reader', 'Color-coded Excel report', '6 feature'] },
  { path: 'dist/ru/index.html', must: ['Потоковый импорт', 'Потоковое SAX-чтение', 'Excel-отчёт с разметкой'] },
];
```

- [ ] **Step 2: Build + smoke (падает)**

Run: `npm run build && npm run smoke` → FAIL.

- [ ] **Step 3: Создать `src/components/FeatureGrid.astro`**

```astro
---
import { content } from '../i18n/content';
const { locale } = Astro.props;
const t = content[locale].features;
---
<section id="features">
  <div class="wrap">
    <h2>{t.heading}</h2>
    <ul class="grid">
      {t.items.map(f => (
        <li><h3>{f.title}</h3><p>{f.body}</p></li>
      ))}
    </ul>
  </div>
</section>
<style>
  .grid { list-style:none; padding:0; display:grid; gap:var(--gap);
          grid-template-columns:repeat(auto-fit, minmax(15rem, 1fr)); }
  .grid li { background:var(--card-bg); border:1px solid var(--border);
             border-radius:var(--radius); padding:1.1rem; }
  .grid h3 { margin:0 0 0.4rem; font-size:1rem; }
</style>
```

- [ ] **Step 4: Добавить `FeatureGrid` в обе страницы** (после `Hero`)

```astro
import FeatureGrid from '../components/FeatureGrid.astro';
...
<Hero locale="en" /><FeatureGrid locale="en" />
```
(для ru — пути `../../components/...` и `locale="ru"`).

- [ ] **Step 5: Build + smoke (проходит)**

Run: `npm run build && npm run smoke` → `smoke OK`.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: feature grid (6 cards) en/ru"
```

---

### Task 6: How it works + inline SVG diagram

**Files:**
- Create: `src/components/HowItWorks.astro`
- Modify: `src/pages/index.astro`, `src/pages/ru/index.astro`
- Modify: `scripts/smoke.mjs`

- [ ] **Step 1: Расширить smoke**

```js
const checks = [
  { path: 'dist/index.html', must: ['How it works', 'Pass 1', 'Pass 2', '<svg'] },
  { path: 'dist/ru/index.html', must: ['Как это работает', 'Проход 1', 'Проход 2', '<svg'] },
];
```

- [ ] **Step 2: Build + smoke (падает)**

Run: `npm run build && npm run smoke` → FAIL.

- [ ] **Step 3: Создать `src/components/HowItWorks.astro`**

```astro
---
import { content } from '../i18n/content';
const { locale } = Astro.props;
const t = content[locale].how;
const p1 = locale === 'en' ? 'Pass 1: data' : 'Проход 1: данные';
const mid = locale === 'en' ? 'outcome map' : 'карта исходов';
const p2 = locale === 'en' ? 'Pass 2: report' : 'Проход 2: отчёт';
---
<section id="how">
  <div class="wrap">
    <h2>{t.heading}</h2>
    <p>{t.body}</p>
    <svg viewBox="0 0 600 80" width="100%" height="80" role="img"
         aria-label={locale === 'en' ? 'two-pass pipeline' : 'конвейер в два прохода'}>
      <rect x="10" y="25" width="140" height="30" rx="6" fill="var(--card-bg)" stroke="var(--border)"/>
      <text x="80" y="44" text-anchor="middle" font-size="12" fill="var(--fg)">{p1}</text>
      <line x1="155" y1="40" x2="200" y2="40" stroke="var(--muted)" stroke-width="2" marker-end="url(#a)"/>
      <rect x="205" y="25" width="150" height="30" rx="6" fill="var(--card-bg)" stroke="var(--border)"/>
      <text x="280" y="44" text-anchor="middle" font-size="12" fill="var(--fg)">{mid}</text>
      <line x1="360" y1="40" x2="405" y2="40" stroke="var(--muted)" stroke-width="2" marker-end="url(#a)"/>
      <rect x="410" y="25" width="150" height="30" rx="6" fill="var(--card-bg)" stroke="var(--border)"/>
      <text x="485" y="44" text-anchor="middle" font-size="12" fill="var(--fg)">{p2}</text>
      <defs><marker id="a" markerWidth="8" markerHeight="8" refX="6" refY="4" orient="auto">
        <path d="M0,0 L8,4 L0,8 z" fill="var(--muted)"/></marker></defs>
    </svg>
  </div>
</section>
<style>
  svg { display:block; max-width:600px; margin-top:1.5rem; }
</style>
```

- [ ] **Step 4: Добавить `HowItWorks` в обе страницы** (после `FeatureGrid`)

- [ ] **Step 5: Build + smoke (проходит)**

Run: `npm run build && npm run smoke` → `smoke OK`.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: how-it-works section with inline svg diagram en/ru"
```

---

### Task 7: Install section (expressive-code snippets)

**Files:**
- Create: `src/components/Install.astro`
- Modify: `src/pages/index.astro`, `src/pages/ru/index.astro`
- Modify: `scripts/smoke.mjs`

- [ ] **Step 1: Расширить smoke**

```js
const checks = [
  { path: 'dist/index.html', must: ['Install', 'excel-import-core', 'excel-import-spring-boot-starter', '0.1.0', 'groupId'] },
  { path: 'dist/ru/index.html', must: ['Установка', 'excel-import-core', '0.1.0'] },
];
```

- [ ] **Step 2: Build + smoke (падает)**

Run: `npm run build && npm run smoke` → FAIL.

- [ ] **Step 3: Создать `src/components/Install.astro`**

```astro
---
import { Code } from 'astro-expressive-code';
import { content } from '../i18n/content';
const { locale } = Astro.props;
const t = content[locale].install;
const v = content.version;
const gradle = `// ${t.gradle}
implementation("org.novgorodtsev.excelimport:excel-import-core:${v}")

// Spring Boot starter (core pulled transitively)
implementation("org.novgorodtsev.excelimport:excel-import-spring-boot-starter:${v}")`;
const maven = `<!-- ${t.maven} -->
<dependency>
  <groupId>org.novgorodtsev.excelimport</groupId>
  <artifactId>excel-import-core</artifactId>
  <version>${v}</version>
</dependency>
<dependency>
  <groupId>org.novgorodtsev.excelimport</groupId>
  <artifactId>excel-import-spring-boot-starter</artifactId>
  <version>${v}</version>
</dependency>`;
---
<section id="install">
  <div class="wrap">
    <h2>{t.heading}</h2>
    <Code code={gradle} lang="kotlin" />
    <Code code={maven} lang="xml" />
  </div>
</section>
```

- [ ] **Step 4: Добавить `Install` в обе страницы** (после `HowItWorks`)

- [ ] **Step 5: Build + smoke (проходит)**

Run: `npm run build && npm run smoke` → `smoke OK`.
(Если expressive-code рендерит код в `<figure>`, убедиться, что `excel-import-core` присутствует в HTML — он должен.)

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: install section with gradle/maven snippets en/ru"
```

---

### Task 8: Quickstart section

**Files:**
- Create: `src/components/Quickstart.astro`
- Modify: `src/pages/index.astro`, `src/pages/ru/index.astro`
- Modify: `scripts/smoke.mjs`

- [ ] **Step 1: Расширить smoke**

```js
const checks = [
  { path: 'dist/index.html', must: ['Quickstart', '@ExcelSheet', 'EmployeeRow', 'ImportConfig', 'ExcelImporter'] },
  { path: 'dist/ru/index.html', must: ['Быстрый старт', '@ExcelSheet', 'EmployeeRow', 'ExcelImporter'] },
];
```

- [ ] **Step 2: Build + smoke (падает)**

Run: `npm run build && npm run smoke` → FAIL.

- [ ] **Step 3: Создать `src/components/Quickstart.astro`**

```astro
---
import { Code } from 'astro-expressive-code';
import { content } from '../i18n/content';
const { locale } = Astro.props;
const t = content[locale].quickstart;
const code = `@ExcelSheet(name = "Employees", headerRow = 0)
@TargetTable(schema = "hr", name = "employee")
public class EmployeeRow {
    @ExcelColumn(header = "Personnel No") @Column("personnel_no") @NotNull
    private Long personnelNo;

    @ExcelColumn(header = "Full Name") @Column("full_name") @NotBlank @Size(max = 200)
    private String fullName;

    @ExcelColumn(header = "Hired At", formats = {"dd.MM.yyyy", "yyyy-MM-dd"})
    @Column("hired_at") @PastOrPresent
    private LocalDate hiredAt;

    @ExcelColumn(header = "Salary") @Column("salary") @DecimalMin("0.00")
    private BigDecimal salary;

    @ExcelColumn(header = "Department", required = false) @Column("department")
    private String department;
    // getters/setters
}

ImportConfig config = ImportConfig.builder()
        .batchSize(1_000)
        .conflictStrategy(ConflictStrategy.doNothing("personnel_no"))
        .reportPath(Path.of("/var/reports/employees-report.xlsx"))
        .maxErrors(10_000)
        .build();

ExcelImporter<EmployeeRow> importer = ExcelImporter.builder(EmployeeRow.class)
        .dataSource(dataSource)
        .config(config)
        .build();

ImportReport report = importer.importFile(Path.of("employees.xlsx"));`;
---
<section id="quickstart">
  <div class="wrap">
    <h2>{t.heading}</h2>
    <Code code={code} lang="java" />
  </div>
</section>
```

- [ ] **Step 4: Добавить `Quickstart` в обе страницы** (после `Install`)

- [ ] **Step 5: Build + smoke (проходит)**

Run: `npm run build && npm run smoke` → `smoke OK`.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: quickstart section with java example en/ru"
```

---

### Task 9: Footer, favicon, _redirects, _headers, robots

**Files:**
- Create: `src/components/Footer.astro`
- Create: `public/favicon.svg`
- Create: `public/_redirects`
- Create: `public/_headers`
- Create: `public/robots.txt`
- Modify: `src/pages/index.astro`, `src/pages/ru/index.astro`
- Modify: `scripts/smoke.mjs`

- [ ] **Step 1: Расширить smoke**

```js
const checks = [
  { path: 'dist/index.html', must: ['MIT', 'Viacheslav Novgorodtsev', 'docs.novgorodtsev.org'] },
  { path: 'dist/ru/index.html', must: ['Лицензия: MIT', 'Вячеслав Новгородцев', 'docs.novgorodtsev.org'] },
  { path: 'dist/_redirects', must: ['www.novgorodtsev.org'] },
  { path: 'dist/_headers', must: ['Strict-Transport-Security'] },
  { path: 'dist/robots.txt', must: ['sitemap'] },
];
```

- [ ] **Step 2: Build + smoke (падает)**

Run: `npm run build && npm run smoke` → FAIL.

- [ ] **Step 3: Создать `src/components/Footer.astro`**

```astro
---
import { content } from '../i18n/content';
const { locale } = Astro.props;
const t = content[locale].footer;
---
<footer>
  <div class="wrap">
    <p>
      <a href={content.githubUrl} rel="noopener">{t.github}</a> ·
      <a href={`${content.githubUrl}/issues`} rel="noopener">{t.issues}</a>
    </p>
    <p>{t.license} · {t.author}</p>
    <p>{t.docs}</p>
  </div>
</footer>
<style>
  footer { padding:2rem 0; border-top:1px solid var(--border); color:var(--muted); font-size:0.9rem; }
  footer p { margin:0.3rem 0; }
</style>
```

- [ ] **Step 4: Добавить `Footer` в обе страницы** (после `Quickstart`, внутри `<Base>`)

- [ ] **Step 5: Создать `public/favicon.svg`**

```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32"><rect width="32" height="32" rx="6" fill="#2563eb"/><text x="16" y="22" font-family="sans-serif" font-size="18" font-weight="700" text-anchor="middle" fill="#fff">xl</text></svg>
```

- [ ] **Step 6: Создать `public/_redirects`**

```
https://www.novgorodtsev.org/* https://novgorodtsev.org/:splat 301!
```

- [ ] **Step 7: Создать `public/_headers`**

```
/*
  Strict-Transport-Security: max-age=63072000; includeSubDomains; preload
  X-Content-Type-Options: nosniff
  Referrer-Policy: strict-origin-when-cross-origin
```

- [ ] **Step 8: Создать `public/robots.txt`**

```
User-agent: *
Allow: /
Sitemap: https://novgorodtsev.org/sitemap-index.xml
```

- [ ] **Step 9: Build + smoke (проходит)**

Run: `npm run build && npm run smoke` → `smoke OK`.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat: footer, favicon, redirects, security headers, robots"
```

---

### Task 10: Final build, visual check, README, GitHub push + Cloudflare setup

**Files:**
- Create: `README.md`

- [ ] **Step 1: Финальная сборка + preview**

Run:
```bash
cd /Users/vvnovg/projects/excel-import-site
npm run build
npm run preview
```
Открыть `http://localhost:4321/` и `http://localhost:4321/ru/` — визуально проверить: hero, 6 карточек, диаграмма, сниппеты с подсветкой и copy-кнопкой, footer, переключатель языка работает, тёмная тема по системной настройке. Остановить preview (Ctrl-C).

- [ ] **Step 2: Создать `README.md`**

```markdown
# excel-import-site

Двуязычный (EN/RU) лендинг библиотеки [excel-import](https://github.com/vvnovg/excel-import).
Astro + Cloudflare Pages, домен novgorodtsev.org.

## Разработка

\`\`\`bash
npm install
npm run dev       # http://localhost:4321
npm run build     # → dist/
npm run smoke     # проверки содержимого dist/
\`\`\`

## Деплой (Cloudflare Pages)

1. Запушить репо на GitHub.
2. Cloudflare Pages → Connect to Git → выбрать репо.
3. Build command: `npm run build`; output dir: `dist`.
4. Привязать custom domains `novgorodtsev.org` и `www.novgorodtsev.org`.
5. SSL/TLS = Full (strict); Always Use HTTPS = ON.
6. `public/_redirects` и `public/_headers` едут с билдом автоматически.

## Структура

- `src/i18n/content.ts` — тексты секций (en/ru) + `SITE_VERSION` + `GITHUB_URL`.
- `src/pages/index.astro` (EN), `src/pages/ru/index.astro` (RU).
- `src/components/*` — секции лендинга.
- `scripts/smoke.mjs` — smoke-тест собранного HTML.
```
(Убрать экранирование тройных backtick при записи в файл.)

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "docs: add readme with dev/deploy instructions"
```

- [ ] **Step 4: Создать репозиторий на GitHub и запушить (ручной шаг)**

Пользователь выполняет:
```bash
gh repo create excel-import-site --public --source=. --push
# либо вручную: создать пустой репо на GitHub, затем:
git remote add origin git@github.com:<user>/excel-import-site.git
git push -u origin main
```

- [ ] **Step 5: Подключить Cloudflare Pages (ручной шаг)**

Пользователь в dashboard Cloudflare:
1. Workers & Pages → Create → Pages → Connect to Git → выбрать `excel-import-site`.
2. Build command `npm run build`, output dir `dist`.
3. Deploy.
4. Custom domains → добавить `novgorodtsev.org` и `www.novgorodtsev.org` (Cloudflare создаст CNAME автоматически).
5. SSL/TLS → Full (strict); Edge Certificates → Always Use HTTPS = ON.
6. Открыть `https://novgorodtsev.org/` и `https://novgorodtsev.org/ru/` — подтвердить, что сайт живёт.

**Готово.**