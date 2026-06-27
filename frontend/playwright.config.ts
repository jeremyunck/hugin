import { defineConfig, devices } from "@playwright/test";

/**
 * V1 UI screenshot harness. This is NOT visual-regression testing — there are no baselines and no
 * pixel diffs. Playwright builds the frontend in mock mode, serves it with `vite preview`, opens
 * each screen via `?mockScreen=`, and captures a full-page screenshot for reviewers to eyeball.
 *
 * A test fails only if a page crashes, fails to render, or the screenshot can't be captured.
 */

const PORT = 4173;
const HOST = "127.0.0.1";
const baseURL = `http://${HOST}:${PORT}`;
const isCI = !!process.env.CI;

export default defineConfig({
  testDir: "./tests/visual",
  // Where the captured PNGs are written; uploaded as the workflow artifact.
  outputDir: "./test-results",
  fullyParallel: true,
  forbidOnly: isCI,
  retries: isCI ? 2 : 0,
  // Keep the preview server and screenshot output deterministic by running serially in CI.
  workers: isCI ? 1 : undefined,
  reporter: isCI ? [["list"], ["github"]] : [["list"]],

  use: {
    baseURL,
    // No live cursor/animation noise, and a trace to debug the rare render failure.
    trace: "on-first-retry",
    screenshot: "only-on-failure"
  },

  projects: [
    {
      name: "desktop",
      use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 1000 } }
    },
    {
      name: "mobile",
      use: { ...devices["Pixel 7"], viewport: { width: 390, height: 844 } }
    }
  ],

  // Serve the mock build with `vite preview`. Locally we build first so `npm run screenshots` is a
  // single self-contained command; in CI the workflow has already run `npm run build:mock` as its own
  // step, so we just preview the existing bundle (no redundant rebuild). The mock build bakes in
  // `VITE_BOUW_MOCK_MODE=true`, so the app boots without a backend.
  webServer: {
    command: isCI ? "npm run preview:mock" : "npm run build:mock && npm run preview:mock",
    url: baseURL,
    timeout: 180_000,
    reuseExistingServer: !isCI
  }
});
