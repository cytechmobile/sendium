# Webhooks

Sendium can call external HTTP endpoints for delivery receipts and mobile-originated messages.

## Delivery Receipt Callbacks

HTTP submissions can include a `dlr-url` query parameter. Sendium carries the callback URL as body-free metadata on the in-memory message, persists it only after the upstream provider accepts or rejects the submission, and calls it for the first terminal provider outcome. Intermediate `ACCEPTD` and `ENROUTE` receipts are acknowledged to the provider but are not forwarded.

Example HTTP submission:

```bash
curl -G http://localhost:8080/sendsms \
  --data-urlencode "username=myuser" \
  --data-urlencode "password=example-password" \
  --data-urlencode "from=Sendium" \
  --data-urlencode "to=306912345678" \
  --data-urlencode "text=Hello from Sendium" \
  --data-urlencode "dlr-url=https://example.com/dlr?msgid=%I&provider=%F&status=%d"
```

### DLR URL Placeholders

| Placeholder | Value |
| :--- | :--- |
| `%d` | Kannel-style DLR status type. |
| `%I` | Gateway message UUID, or empty when unavailable. |
| `%F` | Provider/SMSC message ID, or empty when unavailable (for example, submission rejection before a provider ID exists). |

Substitution is single-pass: inserted IDs are UTF-8 URL-encoded (spaces become `+`) and are never interpreted as templates. Existing URL escapes such as `%20` are preserved. As in the pinned Kannel baseline, `%%` emits one literal `%`; use `%%25` to put an encoded literal percent (`%25`) in the resulting URL. Unknown or unsupported tokens and a trailing `%` remain unchanged. The resulting URL must still be a valid HTTP(S) URI; otherwise the claimed delivery fails as an invalid URI.

**Breaking correction:** `%s` no longer expands to the gateway ID. Use `%I` instead. Kannel uses `%s` for message-data word substitution; Sendium does not implement that token and leaves it unchanged, which normally makes the resulting callback URI invalid. No legacy placeholder mode is provided.

### Kannel compatibility subset

Baseline: Kannel 1.4.5, pinned commit [`4048b41`](https://github.com/markjeee/kannel/blob/4048b41dd9255b3b6fce4ae816eaa93229cd4fdb/gw/urltrans.c), `urltrans_fill_escape_codes`.

| Behavior | Sendium support |
| :--- | :--- |
| `%I`, `%F`, `%d`, single-pass replacement, `%%`, unknown-token preservation | Supported subset; IDs are carried from persisted delivery state. |
| Other Kannel message-data placeholders, including `%s` | Not implemented. |
| `dlr-mask` event selection | Not implemented yet; terminal-only receipt handling remains in effect. |
| Submission response | Intentional difference: HTTP 202 with gateway UUID, not Kannel's textual acknowledgement dialect. |
| Endpoint and authentication errors | `/sendsms`, HTTP 401 for invalid credentials; CGI alias and Kannel error dialect are not implemented. |
| Multipart callback identity semantics and full Kannel placeholder coverage | Not established by this compatibility subset. |

### DLR Status Values

| Value | Meaning |
| :--- | :--- |
| `1` | Delivered. |
| `2` | Failed. |
| `4` | Buffered or accepted for processing; retained as a compatibility mapping and not normally emitted by final-only receipt handling. |
| `8` | Submitted to SMSC; retained as a compatibility mapping and not normally emitted by final-only receipt handling. |

DLR callbacks are sent as HTTP `GET` requests. The durable dispatcher atomically claims PostgreSQL rows on a one-second schedule in non-overlapping batches of up to 100, with each callback dispatched on its own virtual thread and at most 10 callbacks in flight per host. A running batch delays the next check. Claims prevent another Sendium replica from selecting the same callback until the configurable lease expires. Each request has a five-second timeout, and redirects are not followed; only response statuses from `200` to `299` are treated as successful. Failures on attempts 1 through 9 are scheduled one hour later. A failure on attempt 10 marks the row `FAILED`, and pending or failed rows are eligible for cleanup seven days after provider resolution. A malformed callback URI fails immediately after its claimed attempt is counted.

The retry schedule and attempt count survive a Sendium restart, but delivery is at-least-once rather than exactly-once. A crash or storage failure after the receiver accepts a callback can cause another request. Callback handlers must be idempotent and should use the gateway message ID supplied through `%I` as their deduplication key.

## Mobile-Originated Message Forwarding

Incoming MO messages received from an upstream SMPP connection can be forwarded to an HTTP endpoint using SMPP client worker settings.

```properties
outSms.instance.testRoute.forward.mo.url = https://example.com/mo
outSms.instance.testRoute.forward.mo.format = JSON
```

### Forward Formats

| Format | Behavior |
| :--- | :--- |
| `JSON` | Sends a JSON request body. |
| `FORM` | Sends an `application/x-www-form-urlencoded` request body. |

### MO Fields

| Field | Description |
| :--- | :--- |
| `from` | Originating address. |
| `to` | Destination address. |
| `text` | Message text. |
| `timestamp` | Message timestamp. |
| `ingateway` | Inbound gateway identifier. |
| `messageCenter` | Message center value. |
| `dataCoding` | SMPP data coding value. |

### MO URL Placeholders

The forwarding URL may contain placeholders. Values are URL-encoded before replacement.

| Placeholder | Value |
| :--- | :--- |
| `%p` | Originating address (`from`). |
| `%P` | Destination address (`to`). |
| `%a` | Message text. |
| `%t` | Timestamp. |
| `%i` | Inbound gateway identifier. |
| `%I` | Message center. |
| `%o` | Data coding value. |

Example with placeholders:

```properties
outSms.instance.testRoute.forward.mo.url = https://example.com/mo?from=%p&to=%P&text=%a
outSms.instance.testRoute.forward.mo.format = FORM
```

Unlike durable DLR callbacks, MO callbacks are sent as process-local HTTP `POST` requests. HTTP status codes from `200` to `399` are treated as successful. Sendium makes up to 10 attempts with a 120 second delay between failed attempts. The MO retry schedule does not survive a Sendium restart.

## Security Notes

- Use HTTPS webhook URLs in production.
- Treat callback payloads as untrusted input on the receiving system.
- Avoid embedding secrets directly in callback URLs when possible.
- Do not post real callback URLs, credentials, phone numbers, or message bodies in public issues.
