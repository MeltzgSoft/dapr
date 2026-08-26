import { expect, test } from '@playwright/test';

/**
 * The things the Clojure tests cannot see: that the document a browser gets is
 * one it can actually run, that htmx is really there and really wired, and that
 * the stylesheet arrives.
 */
test.describe('the page a browser gets', () => {
  test('loads clean, with htmx and the stylesheet applied', async ({ page }) => {
    const problems: string[] = [];
    page.on('pageerror', (e) => problems.push(`pageerror: ${e.message}`));
    page.on('console', (m) => { if (m.type() === 'error') problems.push(`console: ${m.text()}`); });
    page.on('requestfailed', (r) => problems.push(`failed: ${r.url()}`));

    await page.goto('/');
    await expect(page).toHaveTitle(/Dapr/);

    await expect.poll(() => page.evaluate(() => typeof (window as any).htmx))
      .toBe('object');

    // Served from the app's own WebJar, never a CDN — the page must work with no
    // network at all.
    const htmx = page.locator('script[src^="/assets/htmx.js"]');
    await expect(htmx).toHaveCount(1);

    // If dapr.css had 404'd the body would keep the browser default white.
    await expect.poll(() =>
      page.evaluate(() => getComputedStyle(document.body).backgroundColor),
    ).toBe('rgb(244, 246, 249)');

    expect(problems).toEqual([]);
  });

  test('has every region the fragments address', async ({ page }) => {
    await page.goto('/');
    for (const id of ['workspace', 'sync-bar', 'capacity', 'facets', 'artists',
      'albums', 'track-table', 'controls', 'status-bar', 'overlay', 'activity']) {
      await expect(page.locator(`#${id}`)).toHaveCount(1);
    }
  });

  test('says what to do before a source is picked, rather than showing an empty grid', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#track-table')).toContainText('Pick a source library');
  });
});
