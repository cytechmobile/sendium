package gr.cytech.sendium.auth;

import java.util.Map;

public interface CredentialChangeListener {
    void credentialsChanged(Map<String, CredentialFileWatcher.Credential> updatedCredentials);
}