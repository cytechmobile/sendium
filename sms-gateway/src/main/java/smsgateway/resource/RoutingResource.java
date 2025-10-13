package smsgateway.resource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import smsgateway.auth.SecuredAsAdmin;
import smsgateway.providers.LogProvider;
import smsgateway.routing.config.RoutingRule;
import smsgateway.routing.loader.RoutingRuleLoader;

@Path("/api/admin/routing-rules")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@SecuredAsAdmin
@Tag(name = "Routing Rules", description = "Operations for managing routing rules")
public class RoutingResource {
    private static final Logger LOGGER = LogProvider.getHttpLogger(RoutingResource.class.getName());

    @Inject RoutingRuleLoader ruleLoader;

    @GET
    @Operation(
            summary = "Get Routing Rules",
            description = "Retrieves the current routing rules grouped by rule group.")
    @APIResponse(
            responseCode = "200",
            description = "A map of routing rule groups.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema =
                                    @Schema(
                                            implementation = RoutingRule.class,
                                            type = SchemaType.ARRAY)))
    public Map<String, List<RoutingRule>> getRules() {
        return ruleLoader.getRuleGroups();
    }

    @PUT
    @Operation(summary = "Update Routing Rules", description = "Updates the routing rules.")
    @RequestBody(
            description = "A Map containing the routing rule groups.",
            required = true,
            content =
                    @Content(
                            mediaType = "application/json",
                            schema =
                                    @Schema(
                                            implementation = RoutingRule.class,
                                            type = SchemaType.ARRAY)))
    @APIResponse(responseCode = "200", description = "Routing rules updated successfully.")
    @APIResponse(responseCode = "400", description = "Invalid input.")
    @APIResponse(responseCode = "500", description = "Internal server error.")
    public Response updateRawRules(Map<String, List<RoutingRule>> rules) {
        if (rules == null || rules.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\": \"Request body cannot be empty.\"}")
                    .build();
        }

        if (!ruleLoader.validateRules(rules)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(
                            "{\"error\": \"Invalid rules. The default table must exist with at least 1 rule.\"}")
                    .build();
        }

        boolean ok = ruleLoader.persistRules(rules);

        if (!ok) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(
                            "{\"error\": \"Failed to write routing rules file: "
                                    + ruleLoader.getConfigRulesPath()
                                    + "\"}")
                    .build();
        }

        LOGGER.info("Successfully persisted and reloaded rules");
        return Response.ok("{\"message\": \"Rules updated and reloaded successfully.\"}").build();
    }
}
