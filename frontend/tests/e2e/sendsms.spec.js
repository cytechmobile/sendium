import { test, expect } from '@playwright/test';

test.describe('Send SMS Form', () => {
  test('should allow a user to send an SMS and see a success message', async ({ page }) => {
    // Navigate to the page where the form is located
    await page.goto('/'); // Assuming the form is on the root page

    // Fill out the form
    // Use locators that are resilient to changes. `getByLabel` is good.
    await page.getByLabel('Recipient Phone Number (To)').fill('+1234567890');
    await page.getByLabel('Sender ID (From)').fill('TestSender');
    await page.getByLabel('Message Content').fill('Hello from Playwright!');

    // Click the send button
    // `getByRole` is good for buttons.
    await page.getByRole('button', { name: 'Send Message' }).click();

    // Wait for and assert the success message
    // The snackbar might appear and disappear, so waiting for it specifically is important.
    // We need to find a reliable selector for the snackbar.
    // Looking at the SendSmsForm.vue, the snackbar is a v-snackbar.
    // A common way v-snackbar content is rendered is within a div that has a role like 'status' or 'alert',
    // or a specific class.

    // Option 1: Wait for an element containing the text "Message sent successfully" or "Message sent successfully!".
    // This is often the most straightforward if the text is unique enough.
    // We use a regular expression to catch both variations.
    const successMessageRegex = /Message sent successfully!?/;
    const snackbar = page.locator('div', { hasText: successMessageRegex }).first();

    // Wait for the snackbar to be visible, timeout if it takes too long.
    await expect(snackbar).toBeVisible({ timeout: 10000 }); 

    // Additionally, assert that the snackbar actually contains one of the expected texts.
    // This is somewhat redundant if using hasText in locator, but good for explicit validation.
    const snackbarText = await snackbar.textContent();
    expect(snackbarText).toMatch(successMessageRegex);

    // Alternative if the above is not specific enough:
    // Vuetify snackbars often have a role="status" or role="alert" or a class like .v-snackbar
    // const snackbarByRole = page.getByRole('status', { name: successMessageRegex, exact: false });
    // await expect(snackbarByRole).toBeVisible({ timeout: 10000 });

    // const snackbarByClassAndText = page.locator(`.v-snackbar:has-text("${successMessageRegex}")`);
    // await expect(snackbarByClassAndText).toBeVisible({ timeout: 10000 });
  });
});
