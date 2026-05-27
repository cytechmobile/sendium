package gr.cytech.sendium.core.http;

import com.google.common.base.Strings;
import gr.cytech.sendium.auth.CredentialFileWatcher;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.queue.InMemoryQueueProvider;
import gr.cytech.sendium.core.worker.InMemoryDlrService;
import gr.cytech.sendium.core.worker.MessageState;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Path("/sendsms")
@Tag(name = "sendsms", description = "Operations for sending SMS messages via a Kannel-compatible HTTP interface")
public class KannelResource {
    private static final Logger logger = LoggerFactory.getLogger(KannelResource.class);

    @Inject
    InMemoryQueueProvider queueProvider;

    @Inject
    CredentialFileWatcher credentialFileWatcher;

    @Inject
    InMemoryDlrService dlrService;

    @Operation(
            operationId = "sendSms",
            summary = "Send an SMS message",
            description = "Enqueues an SMS message for delivery. Expects Kannel-compatible GET parameters for authentication, routing, and message payload."
    )
    @APIResponses(value = {
            @APIResponse(
                    responseCode = "202",
                    description = "Message successfully accepted and enqueued. Returns the message serial UUID.",
                    content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(implementation = String.class,
                            examples = "123e4567-e89b-12d3-a456-426614174000"))
            ),
            @APIResponse(
                    responseCode = "400",
                    description = "Bad Request. Missing required parameters like 'to', 'from', or 'text'.",
                    content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(examples = "Missing 'to' parameter"))
            ),
            @APIResponse(
                    responseCode = "401",
                    description = "Unauthorized. Invalid or missing username/password credentials.",
                    content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(examples = "Invalid credentials"))
            ),
            @APIResponse(
                    responseCode = "500",
                    description = "Internal Server Error while processing the SMS.",
                    content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(examples = "Error processing SMS"))
            ),
            @APIResponse(
                    responseCode = "503",
                    description = "Service Unavailable. Temporal failure, usually due to a queue enqueue interruption.",
                    content = @Content(mediaType = MediaType.TEXT_PLAIN, schema = @Schema(examples = "Temporal failure, try again later."))
            )
    })
    @GET
    @PermitAll
    @Produces(MediaType.TEXT_PLAIN)
    public Response receiveSms(
            @Parameter(description = "Username for authentication (preferred over 'user')", required = true) @QueryParam("username") String username,
            @Parameter(description = "Password for authentication (preferred over 'pass')", required = true) @QueryParam("password") String password,
            @Parameter(description = "Sender ID (phone number or alphanumeric string)", required = true) @QueryParam("from") String from,
            @Parameter(description = "Recipient phone number", required = true) @QueryParam("to") String to,
            @Parameter(description = "Message payload (URL encoded)", required = true) @QueryParam("text") String text,
            @Parameter(description = "Character set of the text parameter (e.g., UTF-8, ISO-8859-1)") @QueryParam("charset") String charset,
            @Parameter(description = "User Data Header (UDH) in hex format for concatenated messages or special encoding") @QueryParam("udh") String udh,
            @Parameter(description = "Target SMSC routing ID") @QueryParam("smsc") String smsc,
            @Parameter(description = "Message class (0 = Flash, 1 = ME specific, 2 = SIM specific, 3 = TE specific)") @QueryParam("mclass") Integer mclass,
            @Parameter(description = "Data coding scheme (0 = 7-bit, 1 = 8-bit, 2 = UCS-2)") @QueryParam("coding") Integer coding,
            @Parameter(description = "Validity period in minutes") @QueryParam("validity") Integer validity,
            @Parameter(description = "Deferred delivery time in minutes") @QueryParam("deferred") Integer deferred,
            @Parameter(description = "Delivery report (DLR) callback URL. Supports Kannel variables like %d (status), %A (reply), etc.",
                    example = "https://your-backend.example.com/dlr?msgid=12345&status=%d") @QueryParam("dlr-url") String dlrUrl,
            @Parameter(description = "Protocol Identifier (PID)") @QueryParam("pid") Integer pid,
            @Parameter(description = "Alternative Data Coding Scheme") @QueryParam("alt-dcs") Integer altDcs,
            @Parameter(description = "Return Path Indicator") @QueryParam("rpi") Integer rpi,
            @Parameter(description = "Accounting identifier/owner ID for the message") @QueryParam("account") String account,
            @Parameter(description = "Billing information") @QueryParam("binfo") String binfo,
            @Parameter(description = "Message priority (e.g., 0, 1, 2, 3)") @QueryParam("priority") Integer priority,
            @Parameter(description = "Username for authentication (alternative to 'username') ") @QueryParam("user") String user,
            @Parameter(description = "Password for authentication (alternative to 'password') ") @QueryParam("pass") String pass) {

        String usr = Strings.isNullOrEmpty(user) ? username : user;
        String passwrd = Strings.isNullOrEmpty(pass) ? password : pass;
        validateKannelAuth(usr, passwrd);
        if (to == null || to.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Missing 'to' parameter")
                    .build();
        }

        if (from == null || from.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Missing 'from' parameter")
                    .build();
        }

        if (text == null || text.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Missing 'text' parameter")
                    .build();
        }

        try {
            StandardMessage msg = new StandardMessage();

            msg.from = from;
            msg.to = to;
            msg.message_center = smsc;
            if (Strings.isNullOrEmpty(account)) {
                msg.owner_id = usr;
            } else {
                msg.owner_id = account;
            }

            // Determine the effective charset based on Kannel specifications
            String effectiveCharset = charset;
            if (effectiveCharset == null || effectiveCharset.isEmpty()) {
                int effectiveCoding = 0; // Default to 7 bits
                if (coding != null) {
                    effectiveCoding = coding;
                } else if (udh != null && !udh.isEmpty()) {
                    effectiveCoding = 1; // Sets coding to 8bits if udh is defined
                }

                if (effectiveCoding == 2) {
                    effectiveCharset = "UTF-16BE"; // Defaults to UTF-16BE if coding is UCS-2
                } else {
                    effectiveCharset = "UTF-8"; // Defaults to UTF-8 if coding is 7 bits (or as a safe 8-bit fallback)
                }
            }

            // Decode the message body using the determined charset
            msg.body = decodeTextParam(text, effectiveCharset);

            if (priority != null) {
                msg.priority = priority;
            } else {
                msg.priority = StandardMessage.NORMAL_PRIORITY;
            }

            if (mclass != null) {
                msg.mclass = mclass;
            }

            if (coding != null) {
                msg.dcs = switch (coding) {
                    case 0 -> StandardMessage.DCS_7BIT;
                    case 1 -> StandardMessage.DCS_8BIT;
                    case 2 -> StandardMessage.DCS_16BIT;
                    default -> StandardMessage.DCS_7BIT;
                };
            }

            if (validity != null && validity > 0) {
                msg.ttl = validity;
            }

            if (deferred != null && deferred > 0) {
                msg.ddt = deferred;
            }

            if (udh != null && !udh.isEmpty()) {
                msg.binheader = udh;
            }

            if (pid != null) {
                msg.field1 = pid;
            }

            if (altDcs != null) {
                msg.field2 = altDcs;
            }

            if (rpi != null) {
                msg.field3 = rpi;
            }

            if (binfo != null && !binfo.isEmpty()) {
                msg.field4 = binfo;
            }
            msg.acked = true;
            msg.serial = UUID.randomUUID().toString();
            queueProvider.getRouterQueue().enqueue(msg);
            MessageState state = new MessageState(msg.serial, usr, msg.from, msg.to, dlrUrl);
            dlrService.saveInitialState(state);

            return Response.status(Response.Status.ACCEPTED)
                    .entity(msg.serial)
                    .build();

        } catch (InterruptedException e) {
            logger.error("Failed to enqueue message", e);
            return Response.status(503)
                    .entity("Temporal failure, try again later.")
                    .build();
        } catch (Exception e) {
            logger.error("Error processing SMS", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Error processing SMS")
                    .build();
        }
    }

    public void validateKannelAuth(String username, String password) {
        if (Strings.isNullOrEmpty(username) || Strings.isNullOrEmpty(password)) {
            throw new WebApplicationException(Response.status(Response.Status.UNAUTHORIZED)
                    .entity("Invalid credentials")
                    .build());
        }
        CredentialFileWatcher.Credential cred = credentialFileWatcher.getValidCredentials().get(username);
        if (cred == null || cred.type() != CredentialFileWatcher.CredentialType.HTTP) {
            logger.warn("No username found:{}", username);
            throw new WebApplicationException(Response.status(Response.Status.UNAUTHORIZED)
                    .entity("Invalid credentials")
                    .build());
        }

        if (!password.equals(cred.password())) {
            logger.warn("Invalid password:{}", password);
            throw new WebApplicationException(Response.status(Response.Status.UNAUTHORIZED)
                    .entity("Invalid credentials")
                    .build());
        }
    }

    /**
     * Decodes the text body using the dynamically determined charset.
     * Falls back to UTF-8 if the provided charset is unsupported.
     */
    private String decodeTextParam(String value, String charsetName) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            return URLDecoder.decode(value, charsetName);
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            logger.warn("Unsupported charset provided: {}. Falling back to UTF-8.", charsetName);
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
    }
}
