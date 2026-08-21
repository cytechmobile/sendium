# Webhooks

Sendium can call external HTTP endpoints for delivery receipts and mobile-originated messages.

## Delivery Receipt Callbacks

HTTP submissions can include a `dlr-url` query parameter. Sendium stores the callback URL with the submitted message and calls it for the first terminal provider outcome. Intermediate `ACCEPTD` and `ENROUTE` receipts are acknowledged to the provider but are not forwarded.

Example HTTP submission:

```bash
curl -G http://localhost:8080/sendsms \
  --data-urlencode "username=myuser" \
  --data-urlencode "password=example-password" \
  --data-urlencode "from=Sendium" \
  --data-urlencode "to=306912345678" \
  --data-urlencode "text=Hello from Sendium" \
  --data-urlencode "dlr-url=https://example.com/dlr?msgid=%s&status=%d"
```

### DLR URL Placeholders

| Placeholder | Value |
| :--- | :--- |
| `%d` | Kannel-style DLR status type. |
| `%s` | Gateway message ID when available. |

### DLR Status Values

| Value | Meaning |
| :--- | :--- |
| `1` | Delivered. |
| `2` | Failed. |
| `4` | Buffered or accepted for processing; retained as a compatibility mapping and not normally emitted by final-only receipt handling. |
| `8` | Submitted to SMSC; retained as a compatibility mapping and not normally emitted by final-only receipt handling. |

DLR callbacks are sent as HTTP `GET` requests. The durable dispatcher checks PostgreSQL on a one-second schedule in serial batches of up to 100; a running batch delays the next check rather than overlapping it. Each request has a five-second timeout, and redirects are not followed; the original response status from `200` to `399` is treated as successful. Failures on attempts 1 through 9 are scheduled 120 seconds later. A failure on attempt 10 marks the row `FAILED`, and pending or failed rows are eligible for cleanup seven days after provider resolution. A malformed callback URI fails immediately without starting an HTTP attempt.

The retry schedule and attempt count survive a Sendium restart, but delivery is at-least-once rather than exactly-once. A crash or storage failure after the receiver accepts a callback can cause another request. Callback handlers must be idempotent and should use the gateway message ID supplied through `%s` as their deduplication key.

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
