import { expect, test } from '@playwright/test';
import path from 'node:path';
import { closeSettings, browseTo, createLibrary, openSettings } from '../support/app';
import { MUSIC_DIR, SINK_DIR } from '../support/paths';

test.describe('managing libraries', () => {
  test('creates one by browsing to a real folder', async ({ page }) => {
    await page.goto('/');
    await createLibrary(page, 'E2E Source', MUSIC_DIR);

    await openSettings(page);
    const row = page.locator('.library-row', { hasText: 'E2E Source' });
    await expect(row).toContainText('1 dirs');

    await test.step('the default source/sink chips toggle and stay toggled', async () => {
      const chip = row.getByRole('button', { name: 'source', exact: true });
      await chip.click();
      await expect(page.locator('.library-row', { hasText: 'E2E Source' })
        .getByRole('button', { name: 'source', exact: true })).toHaveClass(/on/);
      await page.locator('.library-row', { hasText: 'E2E Source' })
        .getByRole('button', { name: 'source', exact: true }).click();
      await expect(page.locator('.library-row', { hasText: 'E2E Source' })
        .getByRole('button', { name: 'source', exact: true })).not.toHaveClass(/on/);
    });

    await closeSettings(page);
  });

  test('creates the sink library too', async ({ page }) => {
    await page.goto('/');
    if (await page.locator('#sync-bar option', { hasText: 'E2E Sink' }).count() === 0) {
      await createLibrary(page, 'E2E Sink', SINK_DIR);
    }
    await expect(page.locator('#sync-bar option', { hasText: 'E2E Sink' })).toHaveCount(2);
  });

  test('a library with no roots is refused, and says why', async ({ page }) => {
    await page.goto('/');
    await openSettings(page);
    await page.locator('#overlay').getByRole('button', { name: /Local files/ }).click();
    await page.locator('#overlay input[name="name"]').fill('Nameless');
    await page.locator('#overlay').getByRole('button', { name: 'Save' }).click();

    // Still in the editor: nothing was saved.
    await expect(page.locator('#overlay input[name="name"]')).toHaveValue('Nameless');
    await expect(page.locator('#sync-bar option', { hasText: 'Nameless' })).toHaveCount(0);
    await page.locator('#overlay').getByRole('button', { name: 'Cancel' }).click();
    await closeSettings(page);
  });

  test('edits and deletes a throwaway library', async ({ page }) => {
    await page.goto('/');
    await createLibrary(page, 'E2E Scratch', SINK_DIR);

    await openSettings(page);
    await page.locator('.library-row', { hasText: 'E2E Scratch' })
      .getByRole('button', { name: 'Edit' }).click();
    await expect(page.locator('#overlay input[name="name"]')).toHaveValue('E2E Scratch');

    await test.step('a root can be dropped from the editor', async () => {
      await page.locator('#overlay .root-row').getByRole('button', { name: 'Remove' }).click();
      await expect(page.locator('#overlay')).toContainText('(no roots yet)');
      await page.locator('#overlay').getByRole('button', { name: 'Browse…' }).click();
      await browseTo(page, SINK_DIR);
      await page.getByRole('button', { name: 'Use this folder' }).click();
      await expect(page.locator('#overlay .root-row')).toContainText(path.basename(SINK_DIR));
      await page.locator('#overlay').getByRole('button', { name: 'Save' }).click();
    });

    page.once('dialog', (d) => d.accept());
    await page.locator('.library-row', { hasText: 'E2E Scratch' })
      .getByRole('button', { name: 'Delete' }).click();
    await expect(page.locator('.library-row', { hasText: 'E2E Scratch' })).toHaveCount(0);
    await expect(page.locator('#sync-bar option', { hasText: 'E2E Scratch' })).toHaveCount(0);
    await closeSettings(page);
  });
});
