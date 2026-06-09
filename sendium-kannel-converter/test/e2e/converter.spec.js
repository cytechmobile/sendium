import { expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const testDir = dirname(fileURLToPath(import.meta.url));

function fixture(name) {
  return readFileSync(join(testDir, '..', 'fixtures', name), 'utf8');
}

const customKannelConfig = `group = sendsms-user
username = alice
password = secret
user-allow-ip = 192.0.2.10

group = smsc
smsc = smpp
smsc-id = carrier-one
host = smpp.example.test
port = 2775
smsc-username = carrier-user
smsc-password = carrier-pass
transceiver-mode = true
throughput = 25
`;

const diagnosticSmppboxConfig = `group = smppbox
bearerbox-host = localhost
system-id = downstream-user
password = downstream-password
smppbox-port = 2775
`;

const routingHeavyKannelConfig = fixture('routing-heavy.kannel.conf');
const routingHeavySmppboxConfig = fixture('routing-heavy.smppbox.conf');
const scrollAlignmentConfig = Array.from({ length: 80 }, (_, index) => {
  if (index === 60) {
    return 'get-url = "http://localhost:13013/kannel-in?cd=%c&cs=%C&from=%p&to=%P&text=%b&tstamp=%t&smscid=%i&smsid=%I&dlrstatus=%d&dlrdetails=%A&mclass=%m"';
  }

  return index === 0 ? 'group = sms-service' : `# filler ${index + 1}`;
}).join('\n');

test.beforeEach(async ({ page }) => {
  await page.goto('/');
});

test('renders the default Kannel sample conversion', async ({ page }) => {
  await expect(page.getByRole('heading', { name: 'Kannel config converter' })).toBeVisible();
  await expect(page.getByText('Beta')).toBeVisible();
  await expect(page.getByLabel('kannel.conf editor')).toHaveValue(/group = smsc/);

  const preview = page.getByLabel('Generated Sendium file preview');
  await expect(preview).toContainText('outSms.instance.upstreamA.type = smppclient');
  await expect(preview).toContainText('outSms.instance.upstreamA.host = smpp.provider.example');

  await page.getByRole('tab', { name: 'Generated file credentials.yml' }).click();
  await expect(preview).toContainText('systemId: "legacy-http-user"');

  await page.getByRole('tab', { name: 'Generated file routingTable.conf' }).click();
  await expect(preview).toContainText('upstreamA:owner_id~~to~~to:equals~~startsWith~~!startsWith:legacy-http-user~~447~~447999');
  await expect(preview).toContainText('upstreamA:message_center:equals:vip-a');
  await expect(preview).toContainText('upstreamA:message_center:equals:upstream-a');
  await expect(preview).toContainText('upstreamA::default:');
  await expect(page.getByLabel('Routing syntax legend')).toContainText('target destination worker or table');

  const routedLine = page.locator('.output-line', {
    hasText: 'upstreamA:owner_id~~to~~to:equals~~startsWith~~!startsWith:legacy-http-user~~447~~447999',
  });
  await expect(routedLine.locator('.token-target', { hasText: 'upstreamA' })).toBeVisible();
  await expect(routedLine.locator('.token-attribute', { hasText: 'owner_id' })).toBeVisible();
  await expect(routedLine.locator('.token-operator').filter({ hasText: /^startsWith$/ })).toBeVisible();
  await expect(routedLine.locator('.token-value', { hasText: 'legacy-http-user' })).toBeVisible();

  await expect(page.getByRole('heading', { name: 'Kannel routing coverage' })).toBeVisible();
  await expect(page.getByLabel('Routing compatibility status summary')).toContainText('Active starter mappings');
  await expect(page.getByLabel('Routing compatibility status summary')).toContainText('Warning-only cases');
});

test('updates generated files when the Kannel source changes', async ({ page }) => {
  await page.getByLabel('kannel.conf editor').fill(customKannelConfig);

  const preview = page.getByLabel('Generated Sendium file preview');
  await expect(preview).toContainText('outSms.instance.carrierOne.host = smpp.example.test');
  await expect(preview).toContainText('outSms.instance.carrierOne.tps = 25');

  await page.getByRole('tab', { name: 'Generated file credentials.yml' }).click();
  await expect(preview).toContainText('systemId: "alice"');
  await expect(preview).toContainText('192.0.2.10');
});

test('loads smppbox sample and generates SMPP server settings', async ({ page }) => {
  await page.getByRole('tab', { name: 'smppbox.conf' }).click();
  await page.getByRole('button', { name: 'Load sample' }).click();

  await expect(page.getByLabel('smppbox.conf editor')).toHaveValue(/system-id = downstream-user/);

  const preview = page.getByLabel('Generated Sendium file preview');
  await expect(preview).toContainText('outSms.instance.smppIngress.type = smppserver');
  await expect(preview).toContainText('outSms.instance.smppIngress.srv.port = 2775');

  await page.getByRole('tab', { name: 'Generated file credentials.yml' }).click();
  await expect(preview).toContainText('systemId: "downstream-user"');

  await page.getByRole('tab', { name: 'Generated file routingTable.conf' }).click();
  await expect(preview).toContainText('upstreamA:owner_id~~to:equals~~startsWith:downstream-user~~447');
});

test('diagnostics navigate to the legacy source that needs review', async ({ page }) => {
  await page.getByRole('tab', { name: 'smppbox.conf' }).click();
  await page.getByLabel('smppbox.conf editor').fill(diagnosticSmppboxConfig);
  await page.getByRole('tab', { name: 'kannel.conf' }).click();

  await page.getByLabel('Warnings and manual steps groups').getByRole('button', { name: /Warnings/ }).click();
  await page.locator('.diagnostic-item', { hasText: 'bearerbox-host' }).click();

  await expect(page.getByRole('tab', { name: 'smppbox.conf' })).toHaveAttribute('aria-selected', 'true');
  await expect(page.getByLabel('smppbox.conf editor')).toBeFocused();
});

test('surfaces routing-heavy fixture diagnostics without unsafe generated routes', async ({ page }) => {
  await page.getByLabel('kannel.conf editor').fill(routingHeavyKannelConfig);
  await page.getByRole('tab', { name: 'smppbox.conf' }).click();
  await page.getByLabel('smppbox.conf editor').fill(routingHeavySmppboxConfig);

  await expect(page.getByLabel('Routing compatibility status summary')).toContainText('Active starter mappings');
  await expect(page.getByLabel('Routing compatibility status summary')).toContainText('Unsupported routing cases');
  await expect(page.getByLabel('Routing compatibility status summary')).toContainText('Needs runtime/app support');
  await expect(page.locator('.diagnostics-card')).not.toContainText('request-level `smsc`');

  const routingSummary = page.getByLabel('Routing compatibility status summary');
  await routingSummary.getByRole('button', { name: /Needs runtime\/app support/ }).click();
  await routingSummary.getByRole('button', { name: /Unsupported routing cases/ }).click();
  await routingSummary.getByRole('button', { name: /Active starter mappings/ }).click();
  await expect(routingSummary.locator('.diagnostic-runtime-needed', { hasText: 'group = sms-service' })).toBeVisible();
  await expect(routingSummary.locator('.diagnostic-unsupported', { hasText: 'group = smsc-route' })).toBeVisible();
  await expect(routingSummary.locator('.diagnostic-mapped-active', { hasText: 'forced-smsc' })).toBeVisible();

  await page.getByRole('tab', { name: 'Generated file routingTable.conf' }).click();
  const preview = page.getByLabel('Generated Sendium file preview');
  await expect(preview).toContainText('primaryA:owner_id:equals:account-b');
  await expect(preview).toContainText('primaryA:message_center:equals:vip-a');
  await expect(preview).not.toContainText('downstream-b');
  await expect(preview).not.toContainText('balance');
  await expect(preview).not.toContainText('legacy.example.test');

  await routingSummary.locator('.diagnostic-runtime-needed', { hasText: 'group = sms-service' }).click();
  await expect(page.getByRole('tab', { name: 'kannel.conf' })).toHaveAttribute('aria-selected', 'true');
  await expect(page.getByLabel('kannel.conf editor')).toBeFocused();
  await expect(page.locator('.editor-gutter-runtime-needed', { hasText: '43' })).toBeVisible();
  await expect(page.locator('.editor-gutter-unsupported', { hasText: '40' })).toBeVisible();
  await expect(page.locator('.editor-gutter-mapped-active', { hasText: '4' })).toBeVisible();
});

test('keeps legacy editor gutter aligned with long horizontal lines', async ({ page }) => {
  await page.getByLabel('kannel.conf editor').fill(scrollAlignmentConfig);
  await page.waitForFunction(() => {
    const textarea = document.querySelector('textarea[aria-label="kannel.conf editor"]');
    return textarea
      && textarea.closest('.legacy-editor').querySelectorAll('.editor-gutter-line').length === textarea.value.split('\n').length;
  });

  const metrics = await page.getByLabel('kannel.conf editor').evaluate((textarea) => {
    const editor = textarea.closest('.legacy-editor');
    const gutter = editor.querySelector('.editor-gutter');

    textarea.scrollTop = textarea.scrollHeight;
    textarea.dispatchEvent(new Event('scroll'));

    return {
      textareaScrollTop: textarea.scrollTop,
      gutterScrollTop: gutter.scrollTop,
      textareaRange: textarea.scrollHeight - textarea.clientHeight,
      gutterRange: gutter.scrollHeight - gutter.clientHeight,
    };
  });

  expect(Math.abs(metrics.textareaRange - metrics.gutterRange)).toBeLessThanOrEqual(1);
  expect(Math.abs(metrics.textareaScrollTop - metrics.gutterScrollTop)).toBeLessThanOrEqual(1);
});

test('toggles and persists light appearance', async ({ page }) => {
  await page.evaluate(() => localStorage.setItem('sendium-kannel-converter-theme', 'sendiumDark'));
  await page.reload();

  const app = page.locator('.v-application');
  const shell = page.locator('.app-shell');
  await expect(app).toHaveClass(/v-theme--sendiumDark/);
  await expect(page.locator('html')).toHaveAttribute('data-appearance', 'dark');
  await expect.poll(() => shell.evaluate((element) => getComputedStyle(element).getPropertyValue('--app-bg').trim())).toBe('#08111f');

  await page.getByRole('button', { name: 'Switch to light mode' }).click();

  await expect(app).toHaveClass(/v-theme--sendiumLight/);
  await expect(page.locator('html')).toHaveAttribute('data-appearance', 'light');
  await expect.poll(() => shell.evaluate((element) => getComputedStyle(element).getPropertyValue('--app-bg').trim())).toBe('#f6f2e9');
  await expect(page.getByLabel('kannel.conf editor')).toBeVisible();
  await expect(page.getByLabel('Generated Sendium file preview')).toBeVisible();

  await page.reload();

  await expect(app).toHaveClass(/v-theme--sendiumLight/);
  await expect(page.locator('html')).toHaveAttribute('data-appearance', 'light');

  await page.getByRole('button', { name: 'Switch to dark mode' }).click();
  await expect(app).toHaveClass(/v-theme--sendiumDark/);
  await expect(page.locator('html')).toHaveAttribute('data-appearance', 'dark');
});

test('downloads the active generated file and bundle', async ({ page }) => {
  const fileDownload = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download' }).click();
  expect((await fileDownload).suggestedFilename()).toBe('smsg.properties');

  const bundleDownload = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Bundle' }).click();
  expect((await bundleDownload).suggestedFilename()).toBe('conf.zip');
});

test('copies the active generated file to the clipboard', async ({ browserName, context, page }) => {
  test.skip(browserName !== 'chromium', 'Clipboard permissions are only configured for Chromium here.');

  await context.grantPermissions(['clipboard-read', 'clipboard-write']);

  await page.getByRole('button', { name: 'Copy' }).click();

  await expect(page.getByText('Copied smsg.properties')).toBeVisible();
  await expect.poll(() => page.evaluate(() => navigator.clipboard.readText())).toContain(
    'outSms.instance.upstreamA.type = smppclient',
  );
});
