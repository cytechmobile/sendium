package smsgateway.resource;

import com.google.common.base.Strings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import smsgateway.auth.ApiKeyService;
import smsgateway.auth.SecuredAsAdmin;
import smsgateway.providers.LogProvider;

@Path("/api/admin/api-keys")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@SecuredAsAdmin // Protect the entire resource
@Tag(name = "API Key Management", description = "Operations for managing API keys")
public class ApiKeyResource {
    private static final Logger logger = LogProvider.getHttpLogger(ApiKeyResource.class.getName());

    @Inject ApiKeyService apiKeyService;

    @GET
    @Operation(summary = "Get all API keys", description = "Retrieves a list of all API keys.")
    @APIResponse(
            responseCode = "200",
            description = "A list of API keys.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema =
                                    @Schema(
                                            implementation = ApiKeyService.ApiKey.class,
                                            type = SchemaType.ARRAY)))
    public List<ApiKeyService.ApiKey> getApiKeys() {
        List<ApiKeyService.ApiKey> keys = new ArrayList<>();
        keys.addAll(apiKeyService.getAdminKeys().values());
        keys.addAll(apiKeyService.getMessageKeys().values());
        keys.addAll(apiKeyService.getSmppCredentials().values());
        logger.debug("returning api keys: {}", keys);
        return keys;
    }

    @PUT
    @Operation(summary = "Update API keys", description = "Updates the list of API keys.")
    @RequestBody(
            description = "A JSON array of API key objects.",
            required = true,
            content =
                    @Content(
                            mediaType = "application/json",
                            schema =
                                    @Schema(
                                            implementation = ApiKeyService.ApiKey.class,
                                            type = SchemaType.ARRAY)))
    @APIResponse(
            responseCode = "200",
            description = "The updated list of API keys.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema =
                                    @Schema(
                                            implementation = ApiKeyService.ApiKey.class,
                                            type = SchemaType.ARRAY)))
    @APIResponse(responseCode = "400", description = "Invalid input.")
    public List<ApiKeyService.ApiKey> updateApiKeys(List<ApiKeyService.ApiKey> newKeys) {
        if (newKeys == null
                || newKeys.isEmpty()
                || newKeys.stream().noneMatch(k -> k.type() == ApiKeyService.ApiKeyType.ADMIN)) {
            logger.warn("updateApiKeys error: no admin key");
            throw new BadRequestException("You must have at least one admin key");
        }

        // validate all keys
        for (var key : newKeys) {
            if (key.type() == ApiKeyService.ApiKeyType.ADMIN
                    || key.type() == ApiKeyService.ApiKeyType.MESSAGE) {
                if (Strings.isNullOrEmpty(key.key())
                        || !Strings.isNullOrEmpty(key.systemId())
                        || !Strings.isNullOrEmpty(key.password())) {
                    logger.warn(
                            "updateApiKeys error: {} must only contain the key, not systemId/password",
                            key.type());
                    throw new BadRequestException(
                            key.type() + " keys must contain only the key, not systemId/password");
                }
            } else {
                if (!Strings.isNullOrEmpty(key.key())
                        || Strings.isNullOrEmpty(key.systemId())
                        || Strings.isNullOrEmpty(key.password())) {
                    logger.warn(
                            "SMPP keys must contain valid systemId/password combination and not key");
                    throw new BadRequestException(
                            "SMPP keys must contain valid systemId/password combination and not key");
                }
            }
        }

        try {
            apiKeyService.updateApiKeys(newKeys);
            logger.info("updated api keys");
            return newKeys;
        } catch (Exception e) {
            logger.warn("error updating api keys", e);
            throw new InternalServerErrorException("Failed to update API keys", e);
        }
    }
}
