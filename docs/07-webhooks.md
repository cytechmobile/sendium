# Webhooks

Sendium can call external HTTP endpoints for delivery receipts and mobile-originated messages.

## Delivery Receipt Callbacks

HTTP submissions can include a `dlr-url` query parameter. Sendium stores the callback URL with the submitted message and calls it when the message state changes.

Example HTTP submission:

```bash
curl -G http://localhost:8080/sendsms \
  --data-urlencode "username=myuser" \
  --data-urlencode "password=mypassword" \
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
| `4` | Buffered or accepted for processing. |
| `8` | Submitted to SMSC. |

DLR callbacks are sent as HTTP `GET` requests. HTTP status codes from `200` to `399` are treated as successful. Failed callback attempts are retried up to 10 times with a 120 second delay between attempts.

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

MO callbacks are sent as HTTP `POST` requests. HTTP status codes from `200` to `399` are treated as successful. Failed callback attempts are retried up to 10 times with a 120 second delay between attempts.

## Security Notes

- Use HTTPS webhook URLs in production.
- Treat callback payloads as untrusted input on the receiving system.
- Avoid embedding secrets directly in callback URLs when possible.
- Do not post real callback URLs, credentials, phone numbers, or message bodies in public issues.
