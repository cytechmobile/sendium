package gr.cytech.sendium.performance;

import java.net.URI;

/** URI settings contain no credentials; authentication is separately supplied at deployment. */
public record HttpIngressSettings(URI admissionUri, String username, String password,
        String source, String destination, URI callbackBase) {
    public HttpIngressSettings {
        validateUri(admissionUri);
        if (callbackBase != null) { validateUri(callbackBase); }
        if (username == null || username.isBlank() || password == null || password.isEmpty()
                || source == null || source.isBlank() || destination == null || destination.isBlank()) {
            throw new IllegalArgumentException("Missing HTTP deployment settings");
        }
    }

    private static void validateUri(URI uri) {
        if (uri == null || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Invalid HTTP deployment URI");
        }
    }

    @Override public String toString() {
        return "HttpIngressSettings[credentials=redacted]";
    }
}
