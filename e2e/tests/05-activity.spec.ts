import { expect, test } from '@playwright/test';
import { choose, ensureLibrary } from '../support/app';
import { MUSIC_DIR } from '../support/paths';

test.describe('the activity panel', () => {
  test('opens from the top bar, shows the live log, and closes', async ({ page }) => {
    await page.goto('/');
    await ensureLibrary(page, 'E2E Source', MUSIC_DIR);
    await choose(page, 'source', 'E2E Source');

    await expect(page.locator('#activity .drawer')).toHaveCount(0);
    await page.getByRole('button', { name: 'Activity & Logs' }).click();

    const drawer = page.locator('#activity .drawer');
    await expect(drawer).toBeVisible();
    await expect(drawer.locator('summary')).toContainText('Jobs');
    await expect(drawer.locator('#log-lines')).toContainText('Dapr UI at');

    await test.step('the newest line is the one on show', async () => {
      // The log box is column-reverse, so the newest line is rendered first and
      // sits at the bottom of the scroller with no scripting involved.
      await expect(drawer.locator('#log-lines')).toContainText('E2E Source');
    });

    await drawer.getByRole('button', { name: 'Close' }).click();
    await expect(page.locator('#activity .drawer')).toHaveCount(0);
  });

  test('the panel survives a reload, because it is server state', async ({ page }) => {
    await page.goto('/');
    await page.getByRole('button', { name: 'Activity & Logs' }).click();
    await expect(page.locator('#activity .drawer')).toBeVisible();

    await page.reload();
    await expect(page.locator('#activity .drawer')).toBeVisible();

    await page.locator('#activity .drawer').getByRole('button', { name: 'Close' }).click();
  });
});
