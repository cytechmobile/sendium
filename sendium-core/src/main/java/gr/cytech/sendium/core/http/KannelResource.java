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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Path("/sendsms")
public class KannelResource {
    private static final Logger logger = LoggerFactory.getLogger(KannelResource.class);

    @Inject
    InMemoryQueueProvider queueProvider;

    @Inject
    CredentialFileWatcher  credentialFileWatcher;

    @Inject
    InMemoryDlrService  dlrService;

    @GET
    @PermitAll
    @Produces(MediaType.TEXT_PLAIN)
    public Response receiveSms(
            @QueryParam("username") String username,
            @QueryParam("password") String password,
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("text") String text,
            @QueryParam("charset") String charset,
            @QueryParam("udh") String udh,
            @QueryParam("smsc") String smsc,
            @QueryParam("mclass") Integer mclass,
            @QueryParam("coding") Integer coding,
            @QueryParam("validity") Integer validity,
            @QueryParam("deferred") Integer deferred,
            @QueryParam("dlr-url") String dlrUrl,
            @QueryParam("pid") Integer pid,
            @QueryParam("alt-dcs") Integer altDcs,
            @QueryParam("rpi") Integer rpi,
            @QueryParam("account") String account,
            @QueryParam("binfo") String binfo,
            @QueryParam("priority") Integer priority,
            @QueryParam("user") String user,
            @QueryParam("pass") String pass) {
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