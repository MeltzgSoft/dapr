import { defineConfig, devices } from '@playwright/test';
import { BASE_URL, CONFIG_DIR, PORT, REPO_ROOT, seed } from './support/paths';

seed();

export default defineConfig({
  testDir: './tests',

  // One worker, in order. Dapr is a single-user desktop app: there is one server
  // holding one state atom, so "which library is the source", "is the settings
  // panel open" and "which tracks are ticked" are global. Parallel tests would
  // not be isolated from each other — they would be editing the same session.
  workers: 1,
  fullyParallel: false,

  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: [['list'], ['html', { open: 'never' }]],

  // Generous: several assertions wait on a real background scan of a real
  // directory, and CI runners are slower than a laptop.
  timeout: 90_000,
  expect: { timeout: 20_000 },

  use: {
    baseURL: BASE_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },

  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],

  // The real application, started the way a user starts it. Pointed at a config
  // directory of the suite's own (see support/paths) so it never touches the
  // developer's libraries, and told to keep its hands off the browser — the one
  // Playwright drives is the only one that should open.
  webServer: {
    command: `clojure -M:run --port ${PORT} --no-browser`,
    cwd: REPO_ROOT,
    url: BASE_URL,
    // Never adopt a server someone else started: it would be pointed at the real
    // config directory, and the suite creates and deletes libraries.
    reuseExistingServer: false,
    // A cold JVM plus dependency resolution on a first run.
    timeout: 240_000,
    stdout: 'pipe',
    stderr: 'pipe',
    env: { XDG_CONFIG_HOME: CONFIG_DIR, DAPR_NO_BROWSER: '1' },
  },
});
