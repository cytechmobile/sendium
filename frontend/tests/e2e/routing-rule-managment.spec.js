import { test, expect } from '@playwright/test';

// --- Configuration ---
test.describe.configure({ mode: 'serial' });

const TEST_GROUP_NAME = `e2e-test-group-${Date.now()}`;
const TEST_RULE_NAME_1 = 'e2e-rule-promo';
const TEST_RULE_NAME_2 = 'e2e-rule-support';

test.describe('Routing Rule Management (Live Backend)', () => {

    test('should create a new group', async ({ page }) => {
        await page.goto('/admin/routing-rules');

        await page.getByTestId('add-group-btn').click();
        const dialog = page.getByTestId('group-dialog');
        await expect(dialog).toBeVisible();
        await dialog.locator('#group-name-input').fill(TEST_GROUP_NAME);
        await dialog.getByTestId('group-dialog-save-btn').click();

        await expect(dialog).not.toBeVisible();
        await expect(page.getByTestId('snackbar')).toContainText(`Group "${TEST_GROUP_NAME}" created successfully.`);
        await expect(page.getByTestId(`group-panel-${TEST_GROUP_NAME}`)).toBeVisible();

        const saveButton = page.getByTestId('save-changes-btn');
        await expect(saveButton).toBeEnabled();
        await saveButton.click();
    });

    test('should add two rules to the new group', async ({ page }) => {
        await page.goto('/admin/routing-rules');
        const groupPanel = page.getByTestId(`group-panel-${TEST_GROUP_NAME}`);
        await groupPanel.click(); // Expand the panel

        // --- Add the first rule ---
        await groupPanel.getByTestId(`add-rule-to-group-btn-${TEST_GROUP_NAME}`).click();
        const ruleDialog = page.getByTestId('rule-dialog');
        await expect(ruleDialog).toBeVisible();

        await ruleDialog.locator('#rule-name-input').fill(TEST_RULE_NAME_1);
        await ruleDialog.locator('#rule-text-contains-input').fill('PROMO');
        await ruleDialog.getByTestId('rule-action-send-radio').click();
        await ruleDialog.locator('#rule-destination-id-input').fill('promo-dest');
        await ruleDialog.getByTestId('rule-dialog-save-btn').click();

        await expect(ruleDialog).not.toBeVisible();
        await expect(page.getByTestId(`rule-item-${TEST_GROUP_NAME}-${TEST_RULE_NAME_1}`)).toBeVisible();

        // --- Add the second rule ---
        await groupPanel.getByTestId(`add-rule-to-group-btn-${TEST_GROUP_NAME}`).click();
        await expect(ruleDialog).toBeVisible();

        await ruleDialog.locator('#rule-name-input').fill(TEST_RULE_NAME_2);
        await ruleDialog.locator('#rule-text-contains-input').fill('SUPPORT');
        await ruleDialog.getByTestId('rule-action-send-radio').click();
        await ruleDialog.locator('#rule-destination-id-input').fill('support-dest');
        await ruleDialog.getByTestId('rule-dialog-save-btn').click();

        await expect(ruleDialog).not.toBeVisible();
        await expect(page.getByTestId(`rule-item-${TEST_GROUP_NAME}-${TEST_RULE_NAME_2}`)).toBeVisible();

        const saveButton = page.getByTestId('save-changes-btn');
        await expect(saveButton).toBeEnabled();
        await saveButton.click();
    });

    test('should reorder the rules', async ({ page }) => {
        await page.goto('/admin/routing-rules');
        const groupPanel = page.getByTestId(`group-panel-${TEST_GROUP_NAME}`);
        await groupPanel.click(); // Expand the panel

        // The rules are currently [PROMO, SUPPORT]. We'll move SUPPORT up.
        await page.locator(`#move-rule-up-btn-${TEST_GROUP_NAME}-${TEST_RULE_NAME_2}`).click();

        // After moving, the DOM re-renders. We need to grab the list items again.
        const ruleItems = groupPanel.locator('[data-testid^="rule-item-"]');
        // Now the order should be [SUPPORT, PROMO].
        await expect(ruleItems.first()).toContainText(TEST_RULE_NAME_2);
        await expect(ruleItems.last()).toContainText(TEST_RULE_NAME_1);

        const saveButton = page.getByTestId('save-changes-btn');
        await expect(saveButton).toBeEnabled();
        await saveButton.click();
    });

    test('should delete the rules and the group', async ({ page }) => {
        await page.goto('/admin/routing-rules');
        const groupPanel = page.getByTestId(`group-panel-${TEST_GROUP_NAME}`);
        await groupPanel.click(); // Expand
        // Delete both rules
        await page.getByTestId(`delete-rule-btn-${TEST_GROUP_NAME}-${TEST_RULE_NAME_1}`).click();
        await page.getByTestId('delete-rule-dialog-confirm-btn').click();
        await page.getByTestId(`delete-rule-btn-${TEST_GROUP_NAME}-${TEST_RULE_NAME_2}`).click();
        await page.getByTestId('delete-rule-dialog-confirm-btn').click();
        // Save the deletions
        await page.getByTestId('save-changes-btn').click();
        await expect(page.getByTestId('snackbar')).toContainText('Routing rules saved successfully.');
        // Delete the group
        await page.getByTestId(`delete-group-btn-${TEST_GROUP_NAME}`).click();
        await page.getByTestId('delete-group-dialog-confirm-btn').click();
        await expect(page.getByTestId('snackbar')).toContainText(`Group "${TEST_GROUP_NAME}" deleted`);
        // Verify the group is gone
        await expect(page.getByTestId(`group-panel-${TEST_GROUP_NAME}`)).not.toBeVisible();
    });

});
