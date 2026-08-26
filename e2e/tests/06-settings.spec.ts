import { expect, test } from '@playwright/test';
import { closeSettings, openSettings } from '../support/app';

test.describe('settings', () => {
  test.afterEach(async ({ page }) => {
    // Leave the theme as the suite found it, so a later screenshot or a rerun
    // starts from the same place.
    await openSettings(page);
    await page.locator('#overlay').getByRole('radio', { name: 'System' }).click();
    await expect(page.locator('html')).not.toHaveAttribute('data-theme', /.+/);
    await closeSettings(page);
  });

  test('the theme switches, and sticks across a reload', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('html')).not.toHaveAttribute('data-theme', /.+/);

    await openSettings(page);
    await page.locator('#overlay').getByRole('radio', { name: 'Dark' }).click();

    // Changing the theme answers HX-Refresh: the palette hangs off <html>, which
    // no fragment swap can reach. The settings panel comes back open, because
    // that is server state too.
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    await expect(page.locator('#overlay .modal')).toBeVisible();
    await expect.poll(() =>
      page.evaluate(() => getComputedStyle(document.body).backgroundColor),
    ).toBe('rgb(16, 19, 23)');

    await page.reload();
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
    await expect(page.locator('#overlay input[value="dark"]')).toBeChecked();
  });

  test('the sink-only handling setting persists', async ({ page }) => {
    await page.goto('/');
    await openSettings(page);
    await page.locator('#overlay').getByRole('radio', { name: 'Delete from sink' }).click();
    await expect(page.locator('#overlay input[value="delete"]')).toBeChecked();

    await page.reload();
    await openSettings(page);
    await expect(page.locator('#overlay input[value="delete"]')).toBeChecked();

    await page.locator('#overlay').getByRole('radio', { name: 'Keep on sink' }).click();
    await expect(page.locator('#overlay input[value="keep"]')).toBeChecked();
  });
});
