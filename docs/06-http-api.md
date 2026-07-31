# HTTP API: Sending SMS (Kannel-Compatible)

The application provides a Kannel-compatible HTTP GET endpoint for enqueuing outgoing SMS messages. This makes it incredibly easy to integrate with legacy systems or standard webhooks that expect the classic Kannel parameter structure.

## 📍 Endpoint Overview

* **HTTP Method:** `GET`
* **Path:** `/sendsms`
* **Content-Type (Response):** `text/plain`

## 🔐 Authentication

Authentication is handled via query parameters. The credentials provided must match an active `HTTP` credential type defined in your `credentials.yml` configuration.

You can use either `username` and `password`, or the shorthand `user` and `pass`.

---

## 📥 Request Parameters

All parameters must be passed in the query string.

### Required Parameters

| Parameter | Description | Example |
| :--- | :--- | :--- |
| `username` (or `user`) | The username/system ID for authentication. | `my_api_user` |
| `password` (or `pass`) | The password for authentication. | `example-password` |
| `from` | The Sender ID (can be a phone number or alphanumeric string). | `MyBrand` |
| `to` | The recipient's phone number. | `306910000000` |
| `text` | The message payload. **Must be URL-encoded.** | `Hello%20World` |

### Optional Parameters

| Parameter | Description |
| :--- | :--- |
| `account` | Accounting identifier. If omitted, it defaults to the `username`. |
| `smsc` | Target SMSC routing ID to force a specific outbound route. |
| `coding` | Data coding scheme: `0` (7-bit), `1` (8-bit), or `2` (UCS-2/Unicode). |
| `charset` | Character set of the `text` parameter (e.g., `UTF-8`, `ISO-8859-1`). If omitted, it defaults to `UTF-8` (or `UTF-16BE` if coding is `2`). |
| `udh` | User Data Header in hex format (used for concatenated messages or special encoding). Automatically sets coding to 8-bit if provided without a `coding` parameter. |
| `dlr-url` | Delivery report webhook URL. Supports placeholders like `%d` (Kannel-style DLR status) and `%s` (gateway message ID when available). Must be URL-encoded. |
| `mclass` | Message class: `0` (Flash), `1` (ME specific), `2` (SIM specific), `3` (TE specific). |
| `priority` | Message priority level (e.g., `0`, `1`, `2`, `3`). Defaults to normal priority. |
| `validity` | Validity period in minutes. |
| `deferred` | Deferred delivery time in minutes. |
| `pid` | Protocol Identifier. |
| `alt-dcs` | Alternative Data Coding Scheme. |
| `rpi` | Return Path Indicator. |
| `binfo` | Billing information string. |

---

## 📤 Responses

The API returns standard HTTP status codes along with a plain-text response body.

| HTTP Status | Meaning | Description |
| :--- | :--- | :--- |
| **`202 Accepted`** | **Success** | The message was validated and inserted into Sendium's router queue. The response body contains the unique UUID (serial) of the message. |
| **`400 Bad Request`** | **Error** | Missing a required parameter (`to`, `from`, or `text`). The response body details which parameter is missing. |
| **`401 Unauthorized`** | **Error** | Invalid or missing credentials. |
| **`500 Server Error`** | **Error** | An internal error occurred while parsing or processing the message payload. |
| **`503 Unavailable`** | **Error** | Temporal failure (e.g., the internal queue was interrupted). The client should retry later. |

---

## 📖 Example Request

If you used the generated Quick Start and configured an upstream provider, load its HTTP credentials before submitting a message:

```bash
cd sendium
set -a
. ./.sendium.env
set +a

curl -i -G http://127.0.0.1:8080/sendsms \
  --data-urlencode "username=${SENDIUM_HTTP_USER}" \
  --data-urlencode "password=${SENDIUM_HTTP_PASSWORD}" \
  --data-urlencode "from=Sendium" \
  --data-urlencode "to=306910000000" \
  --data-urlencode "text=Hello from Sendium!"
```

For a manual installation, replace the environment variables with the HTTP `systemId` and `password` from `credentials.yml`. Keep the endpoint local for evaluation. Before external use, terminate HTTPS at a trusted proxy and ensure proxy/access logs omit query strings because authentication credentials, addresses, and message content are URL parameters.

**Example Successful Response (`202 Accepted`):**
```text
123e4567-e89b-12d3-a456-426614174000
```

`202 Accepted` means Sendium validated the message and inserted it into the router queue. It does not prove that a viable route exists, that an upstream SMSC accepted the message, or that a handset received it. Local-only Quick Start installations have no outbound route. Check routing configuration, the SMPP client connection, message lifecycle logs, submit response, and delivery receipt for those later stages.

## Related Documentation

* [Authentication and Security](03-auth-security.md)
* [Webhooks](07-webhooks.md)
* [Documentation Map](DocumentationMap.md)
