// Bouw sandbox-chat screenshot capture.
//
// Drives the local Bouw UI: login -> New sandbox -> ask the agent to create a
// Python script -> wait for the final answer -> screenshot each step.
//
// Run with the headless-shell browser path set, e.g.:
//   PLAYWRIGHT_BROWSERS_PATH=/root/.cache/ms-playwright node capture.mjs
//
// Prereqs: Bouw running on :8080, a seeded login, and SANDBOX_DOCKER_BIN
// pointed at a nonexistent binary so the host-fallback sandbox is used.
// See ../SKILL.md.

import { chromium } from 'playwright';

const BASE = process.env.BOUW_BASE || 'http://localhost:8080';
const USER = process.env.BOUW_USER || 'testuser';
const PASS = process.env.BOUW_PASS || 'Test1234!';
const OUT = process.env.BOUW_SHOTS || '/tmp/shots';

const MSG = 'input[placeholder="Message Bouw…"]'; // note the … glyph
const SEND = '[aria-label="Send message"]';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({
  viewport: { width: 1400, height: 900 }, // app is a 390px column; this matches repo reference shots
  deviceScaleFactor: 2,
});
const page = await ctx.newPage();
const shot = async (name) => { await page.screenshot({ path: `${OUT}/${name}.png` }); console.log('saved', name); };

// 1. Login screen
await page.goto(BASE, { waitUntil: 'networkidle' });
await page.waitForSelector('text=Sign in to your workspace', { timeout: 20000 });
await shot('01-login');

// 2. Sign in
await page.fill('input[placeholder="Enter your username"]', USER);
await page.fill('input[placeholder="Enter your password"]', PASS);
await shot('02-login-filled');
await page.click('button:has-text("Sign in")');

// 3. Home
await page.waitForSelector('[aria-label="Open menu"]', { timeout: 30000 });
await page.waitForSelector(MSG, { timeout: 30000 });
await sleep(1200);
await shot('03-home');

// 4. Menu -> New sandbox
await page.click('[aria-label="Open menu"]');
await page.waitForSelector('button.menu-item:has-text("New sandbox")', { timeout: 10000 });
await shot('04-menu');
await page.click('button.menu-item:has-text("New sandbox")');

// 5. Sandbox chat ready (file tree shows ~/sandbox/<id>, "sandbox" badge)
await page.waitForSelector('text=/~\\/sandbox\\//', { timeout: 30000 });
await page.waitForSelector(MSG, { timeout: 30000 });
await sleep(1500);
await shot('05-sandbox-created');

// 6. Prompt the agent
const prompt = "Create a simple Python script called hello.py that prints 'Hello, World!'. Then show me the file contents.";
await page.fill(MSG, prompt);
await shot('06-prompt-typed');
await page.click(SEND);

// 7. Agent working (tool calls render as <details class="tool-event">)
await sleep(3500);
await shot('07-agent-working');

// Wait for the final assistant answer: non-empty AND text stable across polls.
// Do not rely on the busy flag alone — SSE completion can lag the server.
const deadline = Date.now() + 180000;
let lastLen = -1, stable = 0;
while (Date.now() < deadline) {
  const txt = await page.locator('.message-row-assistant .assistant-response').last().innerText().catch(() => '');
  if (txt && txt.length === lastLen) stable++; else { lastLen = txt ? txt.length : -1; stable = 0; }
  if (txt && txt.length > 10 && stable >= 4) break;
  await sleep(1500);
}
await sleep(1000);

// Collapse tool panels so the final answer is unobstructed, then frame it.
await page.evaluate(() => document.querySelectorAll('details.tool-event').forEach((d) => d.removeAttribute('open')));
await page.locator('.message-row-assistant').last().scrollIntoViewIfNeeded().catch(() => {});
await sleep(800);
await shot('08-final-answer');

// Full conversation
await page.screenshot({ path: `${OUT}/09-conversation-full.png`, fullPage: true });
console.log('saved 09-conversation-full');

await browser.close();
console.log('DONE');
