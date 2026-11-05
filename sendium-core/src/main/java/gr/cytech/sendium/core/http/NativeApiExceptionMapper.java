package gr.cytech.sendium.core.http;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** Gives native-path framework errors the same safe JSON envelope as admission/authentication. */
@Provider
@Priority(Priorities.USER - 100)
public class NativeApiExceptionMapper implements ExceptionMapper<WebApplicationException> {
    @Context UriInfo uri;

    @Override
    public Response toResponse(WebApplicationException failure) {
        String path = uri.getPath();
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (!"api/v1/messages".equals(path) && !"api/v1/messages/".equals(path)) {
            return failure.getResponse();
        }
        int status = failure.getResponse().getStatus();
        String code = switch (status) {
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status >= 500 ? "INTERNAL_ERROR" : "INVALID_REQUEST";
        };
        var reason = Response.Status.fromStatusCode(status);
        String message = reason == null ? "Unable to process request" : reason.getReasonPhrase();
        var response = Response.fromResponse(failure.getResponse()).type(MediaType.APPLICATION_JSON_TYPE)
                .entity(new NativeApiError(code, message));
        if (status == 405) {
            response.allow("POST");
        }
        return response.build();
    }
}
