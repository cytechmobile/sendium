# Routing Table Configuration

The `routingTable.conf` file defines the rules and logic for how messages are evaluated and dispatched to different workers or routing chains. It uses a custom, colon-separated syntax grouped into routing tables (chains).

## 🗂️ File Structure & Tables

Routing logic is organized into **Routing Tables**.
* Tables are defined by enclosing their name in square brackets, such as `[default]`.
* If a table name is not explicitly defined at the top of the file, rules are automatically assigned to the `[default]` table.
* Empty lines are safely ignored.
* Lines starting with `#` are treated as comments. Note that the parser actually captures these comments and uses them as descriptive labels for the immediately following table or rule.

## 📏 Routing Rules Syntax

Inside each table, routing rules are evaluated top-to-bottom. Every rule MUST follow a strict four-part, colon-separated format:

`Target:Attribute:Operator:Value`

| Component | Description | Example |
| :--- | :--- | :--- |
| **Target** | Where to send the message if the rule matches. This can be the name of a specific worker instance (e.g., `testRoute1`, `smppserver.smpp`) or the name of another routing table to evaluate (e.g., `MESSAGE`). | `testRoute1` |
| **Attribute** | The property of the message you want to check. | `type`, `owner_id`, `from`, `to`, `body`, `product` |
| **Operator** | The logical operator used to compare the attribute against the value. | `==`, `equals`, `default` etc. |
| **Value** | The specific value to match. | `0`, `123`, `SenderX` |

---

## 🧮 Available Operators

The routing engine supports a wide array of operators to evaluate message attributes.

**Negation (`!`):** Any operator can be negated by prefixing it with an exclamation mark `!` (e.g., `!equals`, `!startsWith`, `!==`).

### The Default Operator
* `default`: Always matches. Used as a fallback/catch-all rule at the end of a routing table. (Attribute and Value can be left blank).

### String Operators
Used for standard text comparisons:
* `equals`: Exact string match.
* `equalsIgnoreCase`: Case-insensitive string match.
* `matches`: Evaluates the value as a Regular Expression (Regex).
* `startsWith`: Checks if the attribute starts with the given value.
* `endsWith`: Checks if the attribute ends with the given value.
* `greaterThan`, `greaterEqual`, `lessThan`, `lessEqual`: Alphabetical/lexicographical string comparisons.
* `isNull`: Checks if the attribute is null (requires no value).

### Boolean Operators
* `isTrue`: Checks if the attribute evaluates to true.
* `isFalse`: Checks if the attribute evaluates to false.

### Numeric Operators (Typed)
* **Integer:** `==`, `>`, `>=`, `<`, `<=`
---

## ✉️ Message Type Reference

When writing rules that evaluate the `type` attribute (e.g., `MESSAGE:type:==:0`), use the following integer constants to represent different message formats:

| Integer Value | Message Type | Constant Name |
| :--- | :--- | :--- |
| `0` | Standard Text SMS | `MSG_TEXT` |
| `10` | Binary SMS | `MSG_BINARY` |
| `11` | UCS-2 (Unicode) SMS | `MSG_UCS2` |
| `14` | Flash SMS | `MSG_FLASH` |
| `17` | Push SMS | `MSG_PUSH` |
| `18` | Delivery Receipt (DLR) | `MSG_DLR` |

---

## 📖 Example Walkthrough

Let's break down a complete `routingTable.conf` example:

```ini
[default]
MESSAGE:type:==:0
MESSAGE:type:==:11
MESSAGE:type:==:10
smppserver.smpp:type:==:18

[MESSAGE]
testRoute2:owner_id:equals:123
testRoute1:from:equals:SenderX
testRoute::default:
```

### 1. The `[default]` Table
This is the entry point for incoming messages.
* `MESSAGE:type:==:0`: If the message is a standard text SMS (`type 0`), forward it to the `[MESSAGE]` routing table for further evaluation.
* `MESSAGE:type:==:11`: If the message is UCS-2 (`type 11`), forward it to the `[MESSAGE]` table.
* `MESSAGE:type:==:10`: If the message is binary (`type 10`), forward it to the `[MESSAGE]` table.
* `smppserver.smpp:type:==:18`: If the message is a Delivery Receipt (`type 18`), route it directly to the `smppserver.smpp` worker instance.

### 2. The `[MESSAGE]` Table
Messages that get passed here from the `[default]` table are evaluated against these secondary rules.
* `testRoute2:owner_id:equals:123`: If the message belongs to account ID `123`, route it to the `testRoute2` SMPP client.
* `testRoute1:from:equals:SenderX`: If the sender ID (from address) is exactly `SenderX`, route it to the `testRoute1` SMPP client.
* `testRoute::default:`: If none of the above conditions match, this catch-all rule routes the message to the `testRoute` SMPP client.

## Related Documentation

* [SMPP Configuration](04-smpp-configuration.md)
* [HTTP API](06-http-api.md)
* [Documentation Map](DocumentationMap.md)
