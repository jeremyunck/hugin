---
name: bouw-ui-screenshots
description: Use when working in this repo and needing to run the local frontend, verify a UI change, and capture a current screenshot for a PR. Covers starting the Vite app, choosing a mobile-sized viewport, and saving screenshot files into `docs/pr-screenshots`.
---

# Bouw UI Screenshots

Use this workflow when a Bouw frontend change needs a current screenshot from the local app.

## Preconditions

- Work from the repo root.
- Frontend sources live under `frontend/`.
- Store committed screenshots under `docs/pr-screenshots/`.

## Start The Local App

Install frontend dependencies if needed:

```bash
cd frontend
npm install
```

Start Vite on a fixed local port:

```bash
cd frontend
npm run dev -- --host 127.0.0.1 --port 4173
```

If a dev server is already running on that port, reuse it instead of starting a second copy.

## Capture A Screenshot

Prefer a deterministic local browser capture over manual screenshots. This repo already works with a temporary `playwright-core` install plus the cached Chrome-for-Testing binary.

Create a temporary capture tool:

```bash
mkdir -p /tmp/bouw-playwright-shot
npm --prefix /tmp/bouw-playwright-shot install playwright-core
```

Capture the mobile chat screen:

```bash
node -e "const { chromium, devices } = require('/tmp/bouw-playwright-shot/node_modules/playwright-core'); (async() => { const browser = await chromium.launch({ executablePath: '/Users/jnku/Library/Caches/ms-playwright/chromium-1223/chrome-mac-x64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing', headless: true }); const page = await browser.newPage(devices['iPhone 12']); await page.goto('http://127.0.0.1:4173/#/chat', { waitUntil: 'load' }); await page.screenshot({ path: 'docs/pr-screenshots/mobile-<short-description>.png', fullPage: true }); await browser.close(); })().catch(err => { console.error(err); process.exit(1); });"
```

Replace `mobile-<short-description>.png` with a descriptive filename for the changed state.

## Verify The Screenshot

Confirm the file exists and is a PNG:

```bash
ls -lh docs/pr-screenshots/mobile-<short-description>.png
file docs/pr-screenshots/mobile-<short-description>.png
```

Inspect the image before updating the PR. Do not assume the capture is correct just because the command succeeded.

## PR Requirement

- Commit the screenshot file with the UI change.
- Add the screenshot to the PR body.
- For mobile-only changes, include a mobile screenshot.
- For desktop-only changes, include a desktop screenshot.
- For responsive changes, include both.
