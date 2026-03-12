package smsgateway.resource;

import io.smallrye.faulttolerance.api.RateLimit;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeIn;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import smsgateway.auth.SecuredAsMessageSender;
import smsgateway.dto.DlrForwardingPayload;
import smsgateway.services.DlrMappingService;

@SecurityScheme(
        securitySchemeName = "ApiKeyAuth",
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.HEADER,
        apiKeyName = "x-api-key")
@SecurityRequirement(name = "ApiKeyAuth")
@RateLimit(value = 5, window = 1, windowUnit = ChronoUnit.SECONDS)
@Path("/api/dlr")
@SecuredAsMessageSender
@Tag(
        name = "Delivery Reports (DLR)",
        description = "Operations related to message delivery reports")
public class DlrResource {

    @Inject DlrMappingService dlrMappingService;

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Get DLR Status",
            description = "Retrieves the status of all delivery reports.")
    @APIResponse(
            responseCode = "200",
            description = "A list of delivery report payloads.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema =
                                    @Schema(
                                            implementation = DlrForwardingPayload.class,
                                            type = SchemaType.ARRAY)))
    public List<DlrForwardingPayload> getDlrStatus() {
        if (dlrMappingService != null) {
            Collection<DlrForwardingPayload> payloads = dlrMappingService.getAllDlrPayloads();
            if (payloads != null) {
                return new ArrayList<>(payloads);
            }
        }
        return Collections.emptyList();
    }
}
