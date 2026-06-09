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

## Kannel Migration Converter Scope

The browser-only Kannel migration converter supports a focused beta subset of `kannel.conf` and optional smppbox-style config. It generates Sendium starter files and highlights unsupported or manual-review settings; it is not a full Kannel compatibility layer.

### Supported `kannel.conf` Groups

| Kannel group | Converter behavior |
| :--- | :--- |
| `group = sendsms-user` | Generates HTTP credentials in `credentials.yml` and starter routing rules when routing keys resolve. |
| `group = smsc` with `smsc = smpp` | Generates Sendium SMPP client workers in `smsg.properties`. |
| `group = core` | Recognized for diagnostics only; deployment/runtime settings need manual Sendium review. |
| `group = smsbox` | Recognized for diagnostics only; Sendium does not use Kannel bearerbox/smsbox topology. |
| `group = smsbox-route` | Recognized as routing-adjacent but not actively converted. |
| `group = smsc-route` | Recognized as routing-adjacent but not actively converted. |
| `group = sms-service` | Recognized as MO/webhook/application behavior; not converted into outbound routing rules. |

### Actively Converted `kannel.conf` Keys

| Group | Keys |
| :--- | :--- |
| `sendsms-user` | `username`, `password`, `user-allow-ip`, `forced-smsc`, `default-smsc`, `allowed-prefix`, `denied-prefix` |
| `smsc` | `smsc`, `smsc-id`, `id`, `host`, `port`, `smsc-username`, `username`, `smsc-password`, `password`, `system-type`, `transceiver-mode`, `throughput`, `enquire-link-interval`, `keepalive`, `reconnect-delay`, `interface-version`, `source-addr-ton`, `source-addr-npi`, `dest-addr-ton`, `dest-addr-npi`, `address-range`, `allowed-smsc-id`, `preferred-smsc-id` |

### Recognized Manual-Review `kannel.conf` Keys

| Group | Keys |
| :--- | :--- |
| `sendsms-user` | `user-deny-ip`, `concatenation`, `max-messages`, `dlr-url`, `faked-sender` |
| `smsc` | `denied-smsc-id`, `alt-charset`, `log-file`, `log-level`, `max-pending-submits`, `wait-ack`, `validityperiod`, `my-number`, `connect-allow-ip`, `bind-addr-ton`, `bind-addr-npi` |
| `core` | `admin-port`, `admin-password`, `status-password`, `smsbox-port`, `dlr-storage`, `store-type`, `store-location`, `log-file`, `log-level`, `access-log`, `box-allow-ip`, `box-deny-ip` |
| `smsbox` | `bearerbox-host`, `sendsms-port`, `global-sender`, `sendsms-chars`, `log-file`, `log-level`, `access-log`, `mo-recode` |
| `smsbox-route` | `smsbox-id`, `smsbox`, `smsc-id`, `shortcode` |
| `smsc-route` | `smsc-id`, `smsc`, `prefix`, `receiver`, `sender`, `account` |
| `sms-service` | `keyword`, `aliases`, `catch-all`, `text`, `get-url`, `post-url`, `accepted-smsc`, `accepted-account`, `max-messages` |

### Supported smppbox Config

The converter also accepts optional smppbox-style input and converts it into Sendium SMPP server starter config and SMPP credentials.

| smppbox group | Converter behavior |
| :--- | :--- |
| `group = smppbox` | Generates a Sendium `smppserver` worker and SMPP credentials when credentials are complete. |
| `group = opensmppbox` | Treated like `smppbox`. |
| `group = smppbox-user` | Generates SMPP credentials and route-scoped starter rules when possible. |

### Actively Converted smppbox Keys

| Group | Keys |
| :--- | :--- |
| `smppbox` / `opensmppbox` | `id`, `smppbox-port`, `port`, `listen-port`, `smpp-port`, `host`, `listen-host`, `system-id`, `systemid`, `system_id`, `username`, `user`, `password`, `max-connections`, `max-connections-per-ip`, `throughput`, `tps`, `max-pending-submits`, `window-size`, `user-allow-ip`, `allowed-ip`, `allow-ip`, `route-to-smsc`, `allowed-prefix`, `denied-prefix` |
| `smppbox-user` | `system-id`, `systemid`, `system_id`, `username`, `user`, `password`, `user-allow-ip`, `allowed-ip`, `allow-ip`, `throughput`, `tps`, `max-connections`, `route-to-smsc`, `allowed-prefix`, `denied-prefix` |

### Recognized Manual-Review smppbox Keys

| Group | Keys |
| :--- | :--- |
| `smppbox` / `opensmppbox` | `bearerbox-host`, `bearerbox-port`, `log-file`, `log-level` |


## Routing Conversion Notes

Generated `routingTable.conf` rules are starter mappings, not a guarantee of full Kannel routing equivalence. Review route ordering, prefix policy, and fallback behavior before production use.

The UI groups routing compatibility into active starter mappings, warning-only cases, unsupported routing cases, and behavior that needs Sendium runtime, webhook, or application support.

- `forced-smsc` and `default-smsc` map to `owner_id` route rules when the target SMSC is converted.
- `allowed-prefix` and `denied-prefix` map to route-selection conditions when tied to a resolvable route target; verify separately if Sendium must reject disallowed submissions.
- smppbox `route-to-smsc` maps only when it can be scoped to a system-id/account.
- `preferred-smsc-id` and `allowed-smsc-id` map to `message_center` selector routes; `denied-smsc-id` stays warning-only.
- `smsbox-route`, `smsc-route`, and `sms-service` are recognized for diagnostics, but do not generate active Sendium routes in the current beta converter.