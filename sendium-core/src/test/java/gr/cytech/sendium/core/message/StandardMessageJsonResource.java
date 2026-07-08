package gr.cytech.sendium.core.message;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/test/standard-message-json")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class StandardMessageJsonResource {
    @POST
    public StandardMessage echo(StandardMessageRequest request) {
        return request.message();
    }

    public record StandardMessageRequest(StandardMessage message) {}
}
