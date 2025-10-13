package smsgateway.resource;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import smsgateway.auth.SecuredAsAdmin;
import smsgateway.providers.LogProvider;
import smsgateway.smpp.DynamicVendorConfigWatcher;
import smsgateway.smpp.VendorConf;

@Path("/api/admin/vendors")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@SecuredAsAdmin
@Tag(
        name = "SMPP Vendor Configuration",
        description = "Operations for managing SMPP vendor configurations")
public class SmppVendorConfigResource {
    private static final Logger LOGGER =
            LogProvider.getHttpLogger(SmppVendorConfigResource.class.getName());

    @Inject DynamicVendorConfigWatcher dynamicVendorConfigWatcher;

    @GET
    @Operation(summary = "Get all vendors", description = "Retrieves a list of all SMPP vendors.")
    @SecurityRequirement(name = "apiKey")
    @APIResponse(
            responseCode = "200",
            description = "A list of vendors.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema =
                                    @Schema(
                                            implementation = VendorConf.class,
                                            type = SchemaType.ARRAY)))
    public Response getAllVendors() {
        Set<VendorConf> vendors = dynamicVendorConfigWatcher.getCurrentVendorsConfs();
        return Response.ok(vendors).build();
    }

    @GET
    @Path("/{id}")
    @Operation(
            summary = "Get a vendor by ID",
            description = "Retrieves a single SMPP vendor by its ID.")
    @APIResponse(
            responseCode = "200",
            description = "The vendor with the specified ID.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = VendorConf.class)))
    @APIResponse(responseCode = "404", description = "Vendor not found.")
    public Response getVendorById(
            @Parameter(description = "The ID of the vendor to retrieve.", required = true)
                    @PathParam("id")
                    String id) {
        if (id == null || id.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Vendor ID cannot be empty.\"}")
                    .build();
        }
        Optional<VendorConf> vendorOpt =
                dynamicVendorConfigWatcher.getCurrentVendorsConfs().stream()
                        .filter(v -> id.equals(v.getId()))
                        .findFirst();
        if (vendorOpt.isPresent()) {
            return Response.ok(vendorOpt.get()).build();
        } else {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"Vendor with ID '\" + id + \"' not found.\"}")
                    .build();
        }
    }

    @POST
    @Operation(summary = "Create a new vendor", description = "Creates a new SMPP vendor.")
    @RequestBody(
            description = "A JSON object containing the vendor's configuration.",
            required = true,
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = VendorConf.class)))
    @APIResponse(
            responseCode = "201",
            description = "The created vendor.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = VendorConf.class)))
    @APIResponse(responseCode = "400", description = "Invalid input.")
    @APIResponse(responseCode = "409", description = "Vendor already exists.")
    public Response createVendor(VendorConf vendor, @Context UriInfo uriInfo) {
        if (vendor == null || vendor.getId() == null || vendor.getId().trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Vendor data or ID cannot be null or empty.\"}")
                    .build();
        }
        Set<VendorConf> vendors = dynamicVendorConfigWatcher.getCurrentVendorsConfs();
        boolean found = vendors.stream().anyMatch(v -> v.getId().equals(vendor.getId()));
        if (!found) {
            dynamicVendorConfigWatcher.addVendor(vendor);
            try {
                dynamicVendorConfigWatcher.persistCurrentVendorsConf();
                LOGGER.info(
                        "Triggered reload of SMPP vendor configurations after adding vendor {}.",
                        vendor.getId());
            } catch (Exception e) {
                LOGGER.error(
                        "SMPP vendor {} added but failed to reload configurations.",
                        vendor.getId(),
                        e);
            }
            UriBuilder builder = uriInfo.getAbsolutePathBuilder();
            builder.path(vendor.getId());
            return Response.created(builder.build()).entity(vendor).build();
        } else {
            return Response.status(Response.Status.CONFLICT)
                    .entity(
                            "{\"error\":\"Vendor with ID '\" + vendor.getId() + \"' already exists.\"}")
                    .build();
        }
    }

    @PUT
    @Path("/{id}")
    @Operation(summary = "Update a vendor", description = "Updates an existing SMPP vendor.")
    @RequestBody(
            description = "A JSON object containing the vendor's updated configuration.",
            required = true,
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = VendorConf.class)))
    @APIResponse(
            responseCode = "200",
            description = "The updated vendor.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = VendorConf.class)))
    @APIResponse(responseCode = "400", description = "Invalid input.")
    public Response updateVendor(
            @Parameter(description = "The ID of the vendor to update.", required = true)
                    @PathParam("id")
                    String id,
            VendorConf vendor) {
        if (id == null || id.trim().isEmpty() || vendor == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Vendor ID or vendor data cannot be null or empty.\"}")
                    .build();
        }
        // Optional: Validate that vendor.getId(), if present, matches id from path
        if (vendor.getId() != null
                && !vendor.getId().trim().isEmpty()
                && !id.equals(vendor.getId())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(
                            "{\"error\":\"Vendor ID in path ('\" + id + \"') does not match ID in body ('\" + vendor.getId() + \"').\"}")
                    .build();
        }
        // Ensure the ID from the path is set in the vendor object for the update method
        vendor.setId(id);
        dynamicVendorConfigWatcher.removeVendor(vendor);
        dynamicVendorConfigWatcher.addVendor(vendor);
        try {
            dynamicVendorConfigWatcher.persistCurrentVendorsConf();
            LOGGER.info(
                    "Triggered reload of SMPP vendor configurations after updating vendor {}.", id);
        } catch (Exception e) {
            LOGGER.error("SMPP vendor {} updated but failed to reload configurations.", id, e);
        }
        return Response.ok(vendor).build();
    }

    @DELETE
    @Path("/{id}")
    @Operation(summary = "Delete a vendor", description = "Deletes an SMPP vendor.")
    @APIResponse(responseCode = "204", description = "Vendor deleted successfully.")
    @APIResponse(responseCode = "404", description = "Vendor not found.")
    public Response deleteVendor(
            @Parameter(description = "The ID of the vendor to delete.", required = true)
                    @PathParam("id")
                    String id) {
        if (id == null || id.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Vendor ID cannot be empty.\"}")
                    .build();
        }
        var vendor =
                dynamicVendorConfigWatcher.getCurrentVendorsConfs().stream()
                        .filter(v -> v.getId().equals(id))
                        .findFirst();
        if (vendor.isPresent()) {
            dynamicVendorConfigWatcher.removeVendor(vendor.get());
            try {
                dynamicVendorConfigWatcher.persistCurrentVendorsConf();
                LOGGER.info(
                        "Triggered reload of SMPP vendor configurations after deleting vendor {}.",
                        id);
            } catch (Exception e) {
                LOGGER.error("SMPP vendor {} deleted but failed to reload configurations.", id, e);
                // Deletion was successful.
            }
            return Response.noContent().build(); // 204 No Content
        } else {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"Vendor with ID '\" + id + \"' not found.\"}")
                    .build();
        }
    }
}
