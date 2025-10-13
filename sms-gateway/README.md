# SMS Gateway

## Project Overview

This application is a simple SMS gateway designed to receive incoming SMS messages. It exposes a REST API endpoint to accept SMS data in JSON format, logs the received message details, and then routes the message based on configurable, potentially chained, rule groups to appropriate handlers.

## Code Structure

The project is structured into Data Transfer Objects (DTOs), JAX-RS resource classes, and a routing engine.

### `smsgateway.dto.IncomingSms.java`

This class is a Data Transfer Object (DTO) used to model the structure of an incoming SMS message.

*   **Purpose:** To encapsulate the data of an SMS message received by the gateway.
*   **Fields:**
    *   `from` (String): The sender's phone number.
    *   `to` (String): The recipient's phone number (the gateway's number).
    *   `text` (String): The content of the SMS message.
    *   `timestamp` (String): The timestamp when the message was sent/received (ISO 8601 format recommended, e.g., "2023-10-27T10:00:00Z").

### `smsgateway.resource.SmsResource.java`

This class is a JAX-RS resource that defines the REST API endpoints for the SMS gateway.

*   **Role:** To handle HTTP requests related to SMS messages.
*   **Endpoint Exposed:**
    *   `POST /api/sms/incoming`: This endpoint is used to receive new SMS messages.
*   **Logic:**
    *   The `handleIncomingSms` method is triggered when a POST request is made to `/api/sms/incoming`.
    *   It consumes `application/json` and expects a payload matching the `IncomingSms` DTO structure.
    *   **Validation:** It performs a basic check to ensure that the `from`, `to`, and `text` fields are present and not empty in the received JSON payload.
    *   **Logging (Initial):** If the payload is valid, the method prints the details of the received SMS (From, To, Text, Timestamp) to the standard output console.
    *   **Routing:** After initial validation and logging, the `IncomingSms` object is passed to the `SmsRouter` service to be processed by the routing engine.
    *   **Response:**
        *   If the payload is valid, it returns an HTTP 200 OK response with the JSON body: `{"status": "Message received"}`.
        *   If the payload is invalid (e.g., missing required fields), it logs an error message and returns an HTTP 400 Bad Request response with the JSON body: `{"error": "Invalid request payload"}`.
    *   It produces `application/json` for its responses.

## SMS Routing Engine

The SMS Routing Engine is responsible for processing `IncomingSms` objects and directing them to appropriate handlers based on a set of configurable rules. These rules are organized into **groups**, and a message can be **chained** from one group to another, allowing for complex, multi-stage routing logic.

### Routing Rules Configuration (`routing-rules.json`)

Routing rules are defined in a JSON file located at `sms-gateway/src/main/resources/routing-rules.json`.

*   **Structure:** The root of the file is a JSON object. Each key in this object is a **group name** (e.g., `"default"`, `"groupA"`), and the value is an array of rule objects for that group. Processing typically starts with a predefined group named `"default"`.
*   **Rule Object Properties:**
    *   `ruleName` (String): A unique, descriptive name for the rule (e.g., "High Priority Alert").
    *   `priority` (Integer, optional): Defines the order of rule evaluation *within its group*. Lower numbers indicate higher priority. Rules are evaluated in ascending order of their priority. If omitted, the rule is treated as having the lowest priority within its group.
    *   `conditions` (Object): An object containing criteria that an incoming SMS must meet for the rule to match. All specified conditions within this object are ANDed (i.e., all must be true for the rule to match). An empty `conditions` object (`{}`) means the rule matches any message (often used for default/catch-all rules within a group). If the `conditions` field itself is `null` or omitted, the rule will not match any specific message criteria based on content (it might only be a passthrough if `nextRuleGroupName` is defined, but the current router logic would not match it based on message content).
        *   `sender` (String, optional): Matches the `from` field of the SMS.
            *   If it ends with `*`, it's a prefix match (e.g., `"+1800*"` matches `"+18001234567"`).
            *   Otherwise, it's an exact match.
            *   If omitted or `null`, this criterion is a wildcard (matches any sender).
        *   `recipient` (String, optional): Matches the `to` field of the SMS.
            *   If it ends with `*`, it's a prefix match.
            *   Otherwise, it's an exact match.
            *   If omitted or `null`, this criterion is a wildcard (matches any recipient).
        *   `textContains` (String, optional): Performs a case-sensitive substring match on the `text` field of the SMS.
            *   If omitted or `null`, this criterion is a wildcard.
        *   `textMatchesRegex` (String, optional): Matches the `text` field of the SMS against the provided Java regular expression.
            *   If omitted or `null`, this criterion is a wildcard.
    *   `destinationId` (String, optional): An identifier for the target handler (a `MessageDestination` implementation) that should process the SMS if this rule matches and is **not** chaining to another group.
    *   `nextRuleGroupName` (String, optional): The name of the next rule group to process if this rule matches. This enables chained routing.
    *   **Note on `destinationId` and `nextRuleGroupName`:** A rule typically defines *either* a `destinationId` (for a terminal action within the current chain of evaluation for that rule) *or* a `nextRuleGroupName` (to continue processing in another group). If both are present, the current routing logic prioritizes `nextRuleGroupName`. If the chain from `nextRuleGroupName` does not result in the message being handled, the router then checks `destinationId` for the current rule.

*   **Example Rule (Grouped and Chained):**
    ```json
    {
      "default": [
        {
          "ruleName": "ChainToGroupA_For_Critical_Messages",
          "priority": 10,
          "conditions": {
            "sender": "+1800*",
            "textContains": "CRITICAL"
          },
          "nextRuleGroupName": "groupA" 
        },
        {
          "ruleName": "DefaultLog_Unchained",
          "priority": 30,
          "conditions": {
            "sender": "+1GENERAL*"
          },
          "destinationId": "LOG_ONLY_HANDLER"
        }
      ],
      "groupA": [
        {
          "ruleName": "GroupA_Log_Critical_From_Specific_Recipient",
          "priority": 5,
          "conditions": {
            "recipient": "+12223334444",
            "textContains": "CRITICAL"
          },
          "destinationId": "LOG_ONLY_HANDLER"
        }
      ]
    }
    ```
    In this example:
    *   Routing starts with the `"default"` group.
    *   If an SMS from `+1800...` contains "CRITICAL", the `ChainToGroupA_For_Critical_Messages` rule matches, and processing continues in the `"groupA"` group.
    *   In `"groupA"`, if the recipient is `+12223334444` and the message (already known to be CRITICAL from the default group's rule) is processed by `LOG_ONLY_HANDLER`.
    *   If the first rule in `"default"` doesn't match, but an SMS is from `+1GENERAL...`, the `DefaultLog_Unchained` rule matches and directly sends it to `LOG_ONLY_HANDLER`.

### Routing Logic

*   The `smsgateway.routing.router.SmsRouter` service is the core of the routing engine.
*   At application startup, the `smsgateway.routing.loader.RoutingRuleLoader` service loads and parses the rules from `routing-rules.json` into a map of rule groups. Rules within each group are sorted by their `priority`.
*   When an `IncomingSms` message is received by `SmsResource`, it's passed to the `SmsRouter`.
*   The `SmsRouter` begins processing with a **default group** (e.g., named `"default"`).
*   It iterates through the sorted rules of the current group. The **first rule** whose conditions match the message is selected.
*   **Rule Action:**
    *   If the matched rule has a `nextRuleGroupName` defined, processing is **chained** to the specified group. The router will then evaluate rules in that new group. Loop detection is in place to prevent infinite recursion if rule groups chain back to a previously visited group in the current processing path for a single SMS.
    *   If the matched rule has a `destinationId` (and no `nextRuleGroupName` that successfully handled the message in a chain), the message is passed to the corresponding `MessageDestination` handler (e.g., `LOG_ONLY_HANDLER`, `PRINT_ANALYTICS_HANDLER`). This is typically a terminal action for that processing path.
    *   If a rule matches but has neither a valid `nextRuleGroupName` nor a `destinationId` with a configured handler, a warning is logged, and the message processing for that path may stop.
*   If no rule in the current group (or any subsequently chained group) handles the message, an informational message is logged indicating that the SMS was not ultimately handled.

### Key Components

The SMS routing engine is composed of the following main Java classes/interfaces:

*   **Configuration DTOs:**
    *   `smsgateway.routing.config.RoutingRule.java`: DTO representing a single routing rule. Now includes `nextRuleGroupName` for chaining.
    *   `smsgateway.routing.config.RoutingRule.Conditions.java`: Inner DTO representing the conditions for a rule.
*   **Rule Loading:**
    *   `smsgateway.routing.loader.RoutingRuleLoader.java`: An `@ApplicationScoped` service that now loads, parses, and sorts routing rules into a `Map<String, List<RoutingRule>>`, where each key is a group name. It provides methods to get rules for a specific group and the default group name.
*   **Destination Handling:**
    *   `smsgateway.routing.destination.MessageDestination.java`: An interface defining the contract for message destination handlers.
    *   `smsgateway.routing.destination.ConsoleLoggingDestination.java`: An implementation of `MessageDestination`.
    *   `smsgateway.routing.destination.AnalyticsMockDestination.java`: An implementation of `MessageDestination`.
*   **Core Router:**
    *   `smsgateway.routing.router.SmsRouter.java`: An `@ApplicationScoped` service that contains the enhanced core routing logic. It now uses `RoutingRuleLoader` to get rules by group name and recursively processes messages through rule groups, supporting chaining and loop detection.

The routing logic is invoked by `SmsResource` after an SMS is received and validated at the `/api/sms/incoming` endpoint.

## Running the Application

To run the Quarkus application in development mode, navigate to the `sms-gateway` project directory and execute the following command:

```bash
./mvnw quarkus:dev
```
(If you are not using the Maven wrapper, you can use `mvn quarkus:dev`)

The application will typically be accessible at `http://localhost:8080`.

## Testing the Endpoint

You can test the `/api/sms/incoming` endpoint using `curl` or any API testing tool.

### Valid Request Example

Send a POST request with a valid JSON payload:

```bash
curl -X POST -H "Content-Type: application/json" \
-d '{"from": "1234567890", "to": "0987654321", "text": "Hello Quarkus!", "timestamp": "2023-10-27T10:00:00Z"}' \
http://localhost:8080/api/sms/incoming
```

**Expected Success Response (HTTP 200 OK):**

```json
{"status": "Message received"}
```
You should also see the SMS details printed in the console where the Quarkus application is running, followed by logs from the routing engine (indicating which groups and rules were processed) and the matched destination handler(s).

### Invalid Request Example (Missing `text` field)

Send a POST request with an invalid JSON payload (e.g., missing the `text` field):

```bash
curl -X POST -H "Content-Type: application/json" \
-d '{"from": "1234567890", "to": "0987654321", "timestamp": "2023-10-27T10:01:00Z"}' \
http://localhost:8080/api/sms/incoming
```

**Expected Error Response (HTTP 400 Bad Request):**

```json
{"error": "Invalid request payload"}
```
An error message will also be logged to the console.

## Dependencies

The key Quarkus extension used in this project is:

*   `quarkus-resteasy-reactive-jackson`: Provides support for creating RESTful web services using RESTEasy Reactive and JSON serialization/deserialization using Jackson.
*   `quarkus-junit5`: For standard JUnit 5 testing.
*   `quarkus-junit5-mockito`: For mock-based testing in JUnit 5.
*   `rest-assured`: For testing JAX-RS endpoints.


## Application Properties

For this basic setup, no special configurations in `application.properties` are needed beyond the defaults provided by Quarkus for a RESTeasy Reactive Jackson project. The default HTTP port is `8080`.
