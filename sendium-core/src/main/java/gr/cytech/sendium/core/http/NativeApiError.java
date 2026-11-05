package gr.cytech.sendium.core.http;

import io.quarkus.runtime.annotations.RegisterForReflection;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Stable, body-free native API error response. */
@RegisterForReflection
@Schema(name = "NativeApiError", requiredProperties = {"code", "message"})
public record NativeApiError(String code, String message) {
}
