import {test, expect} from '@playwright/test';
test.describe.configure({mode: 'serial'});
const ADMIN_API_KEY = 'your-admin-api-key';

const TEST_KEY_MESSAGE = `test-message-key-${Date.now() % 10000000}`;
const TEST_SYSTEM_ID_SMPP = `test-smpp-id-${Date.now() % 10000000}`;
const TEST_SMPP_PASSWORD_INITIAL = 'Password123!';
const TEST_SMPP_PASSWORD_EDITED = 'Password456-Edited!';

test.describe('API Key Management', () => {

    test.beforeAll(async ({ request }) => {
        console.log('--- E2E Pre-Test Cleanup ---');
        try {
            // 1. Fetch all keys directly from the backend API.
            const response = await request.get('/api/admin/api-keys', {
                headers: { 'X-API-Key': ADMIN_API_KEY }
            });
            expect(response.ok(), 'Cleanup: Failed to fetch API keys. Is the backend running?').toBe(true);
            const allKeys = await response.json();

            // 2. Identify keys that were created by previous test runs.
            const testKeys = allKeys.filter(key =>
                (key.key && key.key.startsWith('test-')) ||
                (key.systemId && key.systemId.startsWith('test-'))
            );

            if (testKeys.length === 0) {
                console.log('No leftover test keys found. System is clean.');
                return; // Nothing to clean up.
            }

            console.log(`Found ${testKeys.length} leftover test keys to delete.`);

            // 3. Create the new list of keys by removing the old test keys.
            const keysToKeep = allKeys.filter(key => !testKeys.find(
                testKey => (testKey.key && testKey.key === key.key) || (testKey.systemId && testKey.systemId === key.systemId)
            ));

            // 4. Send the cleaned list back to the backend.
            const putResponse = await request.put('/api/admin/api-keys', {
                headers: { 'X-API-Key': ADMIN_API_KEY },
                data: keysToKeep
            });
            expect(putResponse.ok(), 'Cleanup: PUT request to delete old keys failed.').toBe(true);
            console.log('Cleanup successful.');

        } catch (error) {
            console.error('CRITICAL: E2E cleanup failed.', error);
            throw new Error('E2E Pre-test cleanup failed. Ensure your backend is running and ADMIN_API_KEY is correct.');
        }
    });

    test('should load the page and display the initial keys', async ({page}) => {
        await page.goto('/');
        await page.getByTestId('API Keys').click();
        await expect(page.getByTestId('api-keys-table')).toBeVisible();
        //find the default admin key
        await expect(page.getByText('your-adm')).toHaveCount(1);
    });

    test('should add a new MESSAGE key', async ({page}) => {
        await page.goto('/admin/api-keys');
        await page.getByTestId('add-key-btn').click();
        const dialog = page.getByTestId('add-edit-dialog');
        await expect(dialog).toBeVisible();

        await dialog.getByTestId('key-type-select').click();
        await page.getByRole('option', {name: 'message'}).click();
        await dialog.locator('#api-key-input').fill(TEST_KEY_MESSAGE);
        await dialog.getByTestId('dialog-save-btn').click();

        // Assertions
        await expect(dialog).not.toBeVisible();
        await expect(page.getByTestId('snackbar')).toContainText('API keys updated successfully!');
        await expect(page.getByTestId(`api-key-row-${TEST_KEY_MESSAGE}`)).toBeVisible();
    });

    test('should add a new SMPP key', async ({page}) => {
        await page.goto('/admin/api-keys');
        await page.getByTestId('add-key-btn').click();
        const dialog = page.getByTestId('add-edit-dialog');
        await expect(dialog).toBeVisible();

        // Fill and submit the form
        await dialog.getByTestId('key-type-select').click();
        await page.getByRole('option', {name: 'smpp'}).click();
        await dialog.locator('#smpp-system-id-input').fill(TEST_SYSTEM_ID_SMPP);
        await dialog.locator('#smpp-password-input').fill(TEST_SMPP_PASSWORD_INITIAL);
        await dialog.getByTestId('dialog-save-btn').click();

        // Assertions
        await expect(dialog).not.toBeVisible();
        await expect(page.getByTestId('snackbar')).toContainText('API keys updated successfully!');
        await expect(page.getByTestId(`api-key-row-${TEST_SYSTEM_ID_SMPP}`)).toBeVisible();
    });

    test('should edit the existing SMPP key', async ({page}) => {
        await page.goto('/admin/api-keys');

        const smppRow = page.getByTestId(`api-key-row-${TEST_SYSTEM_ID_SMPP}`);
        await smppRow.getByTestId(`edit-btn-${TEST_SYSTEM_ID_SMPP}`).click();

        const dialog = page.getByTestId('add-edit-dialog');
        await expect(dialog).toBeVisible();

        // Edit the password
        const passwordInput = dialog.locator('#smpp-password-input');
        await passwordInput.fill(TEST_SMPP_PASSWORD_EDITED);
        await dialog.getByTestId('dialog-save-btn').click();

        // Assertions
        await expect(dialog).not.toBeVisible();
        await expect(page.getByTestId('snackbar')).toContainText('API keys updated successfully!');
    });

    test('should delete the MESSAGE key', async ({page}) => {
        await page.goto('/admin/api-keys');
        const messageRow = page.getByTestId(`api-key-row-${TEST_KEY_MESSAGE}`);
        await messageRow.getByTestId(`delete-btn-${TEST_KEY_MESSAGE}`).click();

        const dialog = page.getByTestId('delete-confirm-dialog');
        await expect(dialog).toBeVisible();
        await dialog.getByTestId('delete-dialog-confirm-btn').click();

        // Assertions
        await expect(dialog).not.toBeVisible();
        await expect(page.getByTestId('snackbar')).toContainText('API key deleted successfully!');
        await expect(page.getByTestId(`api-key-row-${TEST_KEY_MESSAGE}`)).not.toBeVisible();
    });

    test('should delete the SMPP key', async ({page}) => {
        await page.goto('/admin/api-keys');
        const smppRow = page.getByTestId(`api-key-row-${TEST_SYSTEM_ID_SMPP}`);
        await smppRow.getByTestId(`delete-btn-${TEST_SYSTEM_ID_SMPP}`).click();

        const dialog = page.getByTestId('delete-confirm-dialog');
        await expect(dialog).toBeVisible();
        await dialog.getByTestId('delete-dialog-confirm-btn').click();

        // Assertions
        await expect(dialog).not.toBeVisible();
        await expect(page.getByTestId('snackbar')).toContainText('API key deleted successfully!');
        await expect(page.getByTestId(`api-key-row-${TEST_SYSTEM_ID_SMPP}`)).not.toBeVisible();
    });
});
