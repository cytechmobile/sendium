package gr.cytech.sendium.core.http;

/** Stable, body-free native API error response. */
public record NativeApiError(String code, String message) {
}
