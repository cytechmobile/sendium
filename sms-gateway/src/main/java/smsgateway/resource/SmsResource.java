package smsgateway.resource;

import com.cloudhopper.commons.charset.CharsetUtil;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import smsgateway.auth.SecuredAsMessageSender;
import smsgateway.dto.DlrForwardingPayload;
import smsgateway.dto.IncomingSms;
import smsgateway.providers.LogProvider;
import smsgateway.routing.router.SmsRouter;
import smsgateway.services.DlrMappingService;

@Path("/api/sms")
@SecuredAsMessageSender
@Tag(name = "SMS", description = "Operations for sending SMS messages")
public class SmsResource {
    private static final Logger LOGGER = LogProvider.getHttpLogger(SmsResource.class.getName());
    private final SmsRouter smsRouter;
    private final DlrMappingService dlrMappingService;

    @Inject
    public SmsResource(SmsRouter smsRouter, DlrMappingService dlrMappingService) {
        this.smsRouter = smsRouter;
        this.dlrMappingService = dlrMappingService;
    }

    @POST
    @Path("/send")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Send an SMS message", description = "Sends a new SMS message.")
    @RequestBody(
            description = "A JSON object containing the SMS message details.",
            required = true,
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = IncomingSms.class)))
    @APIResponse(
            responseCode = "200",
            description = "The message was accepted for delivery.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = MessageResponse.class)))
    @APIResponse(
            responseCode = "400",
            description = "Invalid input",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = MessageResponse.class)))
    public Response handleIncomingSms(IncomingSms incomingSms) {
        if (incomingSms != null
                && incomingSms.getFrom() != null
                && !incomingSms.getFrom().isEmpty()
                && incomingSms.getTo() != null
                && !incomingSms.getTo().isEmpty()
                && incomingSms.getText() != null
                && !incomingSms.getText().isEmpty()) {

            LOGGER.info(
                    "Received SMS: From: {}, To: {}, Text: {}, Timestamp: {}",
                    incomingSms.getFrom(),
                    incomingSms.getTo(),
                    incomingSms.getText(),
                    incomingSms.getTimestamp());
            incomingSms.setCoding(CharsetUtil.NAME_GSM);

            String internalId = UUID.randomUUID().toString();
            incomingSms.setInternalId(internalId);

            // Create and store DLR payload
            DlrForwardingPayload payload = new DlrForwardingPayload();
            payload.setForwardingId(internalId);
            payload.setStatus("ACCEPTED"); // Consider using constants for statuses
            payload.setReceivedAt(Instant.now());
            payload.setFromAddress(incomingSms.getFrom());
            payload.setToAddress(incomingSms.getTo());
            payload.setForwardUrl(incomingSms.getForwardUrl());
            dlrMappingService.storeDlrPayload(internalId, payload);

            smsRouter.route(incomingSms);
            LOGGER.info(
                    "Routed SMS: From: {}, To: {}, Text: {}, Timestamp: {} id:{}",
                    incomingSms.getFrom(),
                    incomingSms.getTo(),
                    incomingSms.getText(),
                    incomingSms.getTimestamp(),
                    internalId);
            incomingSms.setCoding(CharsetUtil.NAME_GSM);

            return Response.ok(new MessageResponse("Message received", internalId)).build();
        } else {
            LOGGER.warn(
                    "Invalid SMS payload received. From: {}, To: {}, Text: {}",
                    incomingSms != null ? incomingSms.getFrom() : "null",
                    incomingSms != null ? incomingSms.getTo() : "null",
                    incomingSms != null ? incomingSms.getText() : "null");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new MessageResponse("Invalid request payload"))
                    .build();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public static class MessageResponse {

        private String status;
        private String internalId;
        private String error;

        // Default constructor is needed by some JSON libraries
        public MessageResponse() {}

        public MessageResponse(String status, String internalId) {
            this.status = status;
            this.internalId = internalId;
        }

        public MessageResponse(String error) {
            this.error = error;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getInternalId() {
            return internalId;
        }

        public String getError() {
            return error;
        }
    }
}
