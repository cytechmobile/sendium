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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

@Path("/api/v1/messages")
@NativeApiAuthenticated
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class NativeMessageResource {
    private static final Logger logger = LoggerFactory.getLogger(NativeMessageResource.class);
    private static final int MAX_REQUEST_BYTES = 32768;
    @Inject ObjectMapper mapper;
    @Inject Instance<OutboundCoordinator<StandardMessage>> coordinators;

    @POST
    @Blocking
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
            message.dlrReturnMetadata = DlrReturnMetadata.http(principal.accountId(), message.from, message.to, request.callbackUrl());
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

    public record Accepted(String messageId, String status) {
    }
}
