# Sendium Kannel Converter

Browser-only beta migration assistant for turning a narrow subset of `kannel.conf` into Sendium starter files.

## Local Development

```bash
npm install
npm run dev
```

## Verification

```bash
npm test
npx playwright install chromium
npm run test:e2e
npm run check
```

## GitHub Pages

The converter is published by `.github/workflows/kannel-converter-pages.yml` from the Vite production build in `dist/`.

Public URL: <https://cytechmobile.github.io/sendium/>

## Current Beta Scope

- Parse Kannel-style `group = ...` blocks and `key = value` lines.
- Generate starter `credentials.yml` from `group = sendsms-user` entries.
- Generate starter `smsg.properties` from SMPP `group = smsc` entries.
- Convert optional smppbox/opensmppbox-style ingress config into Sendium `smppserver` starters and SMPP credentials.
- Generate `routingTable.conf` starters for resolvable Kannel routing keys, including user `forced-smsc` / `default-smsc`, prefix constraints, smppbox `route-to-smsc`, and SMSC selector lists.
- Preserve a fallback route for the first converted SMPP client when possible.
- Highlight unsupported or manual-review settings without uploading data anywhere.
- Attach inline next steps and Sendium/Kannel reference links to diagnostics.
- Cover the core converter behavior with fixture-based tests in `test/fixtures`.

The converter runs entirely in the browser.

## Routing Conversion Notes

Generated `routingTable.conf` rules are starter mappings, not a guarantee of full Kannel routing equivalence. Review route ordering, prefix policy, and fallback behavior before production use.

- `forced-smsc` and `default-smsc` map to `owner_id` route rules when the target SMSC is converted.
- `allowed-prefix` and `denied-prefix` map to route-selection conditions when tied to a resolvable route target; verify separately if Sendium must reject disallowed submissions.
- smppbox `route-to-smsc` maps only when it can be scoped to a system-id/account.
- `preferred-smsc-id` and `allowed-smsc-id` map to `message_center` selector routes; `denied-smsc-id` stays warning-only.
