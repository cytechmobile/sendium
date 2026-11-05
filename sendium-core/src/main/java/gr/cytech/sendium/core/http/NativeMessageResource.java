package gr.cytech.sendium.core.http;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import gr.cytech.sendium.core.message.DlrReturnMetadata;
import gr.cytech.sendium.core.message.StandardMessage;
import gr.cytech.sendium.core.outbound.OutboundCoordinator;
import gr.cytech.sendium.core.outbound.OutboundWork.SourceId;
import gr.cytech.sendium.core.storage.OutboundStorageException;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.callbacks.Callback;
import org.eclipse.microprofile.openapi.annotations.callbacks.CallbackOperation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.media.SchemaProperty;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

@Path("/api/v1/messages")
@NativeApiAuthenticated
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Native messages", description = "Recommended SMS submission API for new integrations")
@SecurityScheme(securitySchemeName = "NativeApiKey", type = SecuritySchemeType.HTTP, scheme = "bearer",
        description = "Opaque API key from an HTTP credential with a configured accountId; not a JWT")
@SecurityRequirement(name = "NativeApiKey")
public class NativeMessageResource {
    private static final Logger logger = LoggerFactory.getLogger(NativeMessageResource.class);
    private static final int MAX_REQUEST_BYTES = 32768;
    @Inject ObjectMapper mapper;
    @Inject Instance<OutboundCoordinator<StandardMessage>> coordinators;

    @POST
    @Blocking
    @Operation(operationId = "submitNativeMessage", summary = "Accept one SMS for outbound processing",
            description = "JSON only; no query parameters or caller-selected account/route. A 202 response confirms " +
                    "memory-backed admission, not provider acceptance, delivery or restart durability. Maximum body: 32768 bytes.")
    @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = NativeMessageRequest.class)))
    @APIResponses({
            @APIResponse(responseCode = "202", description = "Admitted; acceptance does not mean delivery",
                    content = @Content(schema = @Schema(implementation = Accepted.class))),
            @APIResponse(responseCode = "400", description = "Invalid JSON, fields, encoding or query parameters",
                    content = @Content(schema = @Schema(implementation = NativeApiError.class))),
            @APIResponse(responseCode = "401", description = "Missing, invalid or wrong-type Bearer credentials",
                    content = @Content(schema = @Schema(implementation = NativeApiError.class))),
            @APIResponse(responseCode = "403", description = "IP not allowed or accountId missing from credential",
                    content = @Content(schema = @Schema(implementation = NativeApiError.class))),
            @APIResponse(responseCode = "405", description = "Only POST is supported",
                    content = @Content(schema = @Schema(implementation = NativeApiError.class))),
            @APIResponse(responseCode = "406", description = "Response must accept application/json",
                    content = @Content(schema = @Schema(implementation = NativeApiError.class))),
            @APIResponse(responseCode = "413", description = "Request body exceeds 32768 bytes",
                    content = @Content(schema = @Schema(implementation = NativeApiError.class))),
            @APIResponse(responseCode = "415", description = "Content-Type must be application/json",
                    content = @Content(schema = @Schema(implementation = NativeApiError.class))),
            @APIResponse(responseCode = "500", description = "Unexpected admission failure",
                    content = @Content(schema = @Schema(implementation = NativeApiError.class))),
            @APIResponse(responseCode = "503", description = "Admission unavailable or at capacity; retry later",
                    content = @Content(schema = @Schema(implementation = NativeApiError.class)))
    })
    @Callback(name = "deliveryReceipt", callbackUrlExpression = "{$request.body#/callbackUrl}",
            operations = @CallbackOperation(method = "POST", summary = "Final delivery outcome",
                    description = "Optional; requires DLR persistence. At-least-once; deduplicate by messageId. " +
                            "Any 2xx succeeds; no redirects. Failure retries after one hour, up to 10 attempts.",
                    requestBody = @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(type = SchemaType.OBJECT, requiredProperties = {"messageId", "status", "errorCode"},
                                    additionalProperties = Schema.False.class, properties = {
                                        @SchemaProperty(name = "messageId", type = SchemaType.STRING, format = "uuid"),
                                        @SchemaProperty(name = "status", type = SchemaType.STRING, enumeration = {"delivered", "failed"}),
                                        @SchemaProperty(name = "errorCode", type = SchemaType.STRING, nullable = true)
                                    }))), responses = @APIResponse(responseCode = "2XX", description = "Receipt accepted by receiver")))
    public Response submit(InputStream body, @Context SecurityContext security, @Context UriInfo uri) {
        if (!uri.getQueryParameters().isEmpty()) {
            return error(400, "INVALID_REQUEST", "Query parameters are not supported; use a JSON body");
        }
        NativeMessageRequest request;
        try {
            byte[] bytes = body.readNBytes(MAX_REQUEST_BYTES + 1);
            if (bytes.length > MAX_REQUEST_BYTES) {
                return error(413, "PAYLOAD_TOO_LARGE", "Request body must not exceed 32768 bytes");
            }
            request = NativeMessageRequest.parse(mapper.reader()
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes));
        } catch (JsonProcessingException invalid) {
            return error(400, "INVALID_REQUEST", "Request must contain one valid JSON object");
        } catch (IllegalArgumentException invalid) {
            return error(400, "INVALID_REQUEST", invalid.getMessage());
        } catch (IOException invalid) {
            return error(400, "INVALID_REQUEST", "Unable to read request body");
        }
        var principal = (NativeApiPrincipal) security.getUserPrincipal();
        StandardMessage message = new StandardMessage();
        UUID id = UUID.randomUUID();
        message.serial = id.toString();
        message.owner_id = principal.accountId();
        message.from = request.from();
        message.to = request.to();
        message.body = request.text();
        message.priority = request.priority();
        boolean ucs2 = "ucs2".equals(request.encoding());
        message.type = ucs2 ? StandardMessage.MSG_UCS2 : StandardMessage.MSG_TEXT;
        message.dcs = ucs2 ? StandardMessage.DCS_16BIT : StandardMessage.DCS_7BIT;
        message.acked = request.callbackUrl() != null;
        if (message.acked) {
            message.dlrReturnMetadata = DlrReturnMetadata.httpJson(principal.accountId(), message.from, message.to, request.callbackUrl());
        }
        try {
            if (coordinators.isUnsatisfied()) {
                return error(503, "ADMISSION_UNAVAILABLE", "Message admission is unavailable; try again later");
            }
            coordinators.get().accept(new SourceId(id), message);
        } catch (OutboundStorageException failure) {
            return switch (failure.reason()) {
                case CAPACITY_EXCEEDED, UNAVAILABLE, OWNERSHIP_CONFLICT ->
                    error(503, "ADMISSION_UNAVAILABLE", "Message admission is unavailable; try again later");
                case UNSUPPORTED -> error(400, "INVALID_REQUEST", "Unsupported message submission");
                case INVALID_TRANSITION -> error(500, "INTERNAL_ERROR", "Unable to admit message");
            };
        } catch (RuntimeException failure) {
            logger.error("Native message admission failed failureType={}", failure.getClass().getName());
            return error(500, "INTERNAL_ERROR", "Unable to admit message");
        }
        return Response.accepted(new Accepted(message.serial, "accepted")).build();
    }

    private static Response error(int status, String code, String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE).entity(new NativeApiError(code, message)).build();
    }

    @RegisterForReflection
    @Schema(name = "NativeMessageAccepted", requiredProperties = {"messageId", "status"})
    public record Accepted(@Schema(format = "uuid") String messageId,
                           @Schema(enumeration = "accepted") String status) {
    }
}
