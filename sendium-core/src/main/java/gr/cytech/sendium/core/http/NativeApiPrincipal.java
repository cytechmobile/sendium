package gr.cytech.sendium.core.http;

import java.security.Principal;

/** Authenticated account context; never retains the API key. */
public record NativeApiPrincipal(String accountId, String product) implements Principal {
    @Override
    public String getName() {
        return accountId;
    }
}
