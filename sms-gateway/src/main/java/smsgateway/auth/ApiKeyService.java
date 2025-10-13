package smsgateway.auth;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class ApiKeyService {
    public static String ADMIN_DEFAULT_KEY = "your-admin-api-key";

    private Map<String, ApiKey> messageKeys;
    private Map<String, ApiKey> smppCredentials;
    private Map<String, ApiKey> adminKeys;

    @Inject ObjectMapper objectMapper;

    @ConfigProperty(
            name = "sms.gateway.api.keys.config.file.path",
            defaultValue = "conf/api-keys.json")
    String keysConfigPath;

    @PostConstruct
    void init() {
        loadApiKeys();
    }

    private void loadApiKeys() {
        final var confFilePath = Paths.get(keysConfigPath);
        if (!Files.exists(Paths.get(keysConfigPath))) {
            if (confFilePath.getParent() != null) {
                try {
                    Files.createDirectories(confFilePath.getParent());
                } catch (Exception e) {
                    Log.warn(
                            "api keys config file does not exist and failed to create parent directories: "
                                    + keysConfigPath,
                            e);
                }
            }
            try {
                objectMapper
                        .writerWithDefaultPrettyPrinter()
                        .writeValue(
                                confFilePath.toFile(), List.of(ApiKey.admin(ADMIN_DEFAULT_KEY)));
                Files.writeString(
                        confFilePath, "", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception e) {
                Log.warn(
                        "api keys config file does not exist and failed to create an empty one: "
                                + keysConfigPath,
                        e);
            }
        }
        try {
            var keysJson = Files.readString(confFilePath, StandardCharsets.UTF_8);
            var keys = objectMapper.readValue(keysJson, new TypeReference<List<ApiKey>>() {});
            var adminMap = new HashMap<String, ApiKey>();
            var messageMap = new HashMap<String, ApiKey>();
            var smppMap = new HashMap<String, ApiKey>();
            for (var key : keys) {
                if (key.type == ApiKeyType.ADMIN) {
                    adminMap.put(key.key, key);
                } else if (key.type == ApiKeyType.SMPP) {
                    smppMap.put(key.systemId, key);
                } else {
                    messageMap.put(key.key, key);
                }
            }
            this.messageKeys = messageMap;
            this.adminKeys = adminMap;
            this.smppCredentials = smppMap;
            Log.infof("Successfully loaded API keys from %s", keysConfigPath);
        } catch (Exception e) {
            Log.errorf(e, "Failed to load API keys from %s", keysConfigPath);
        }
    }

    public boolean isMessageKeyValid(String key) {
        return messageKeys.containsKey(key) || isAdminKeyValid(key);
    }

    public boolean isAdminKeyValid(String key) {
        return adminKeys.containsKey(key);
    }

    public boolean isSmppCredentialsValid(String systemId, String password) {
        var key = smppCredentials.get(systemId);
        return key != null && Strings.nullToEmpty(key.password).equals(password);
    }

    public Map<String, ApiKey> getMessageKeys() {
        return new HashMap<>(messageKeys); // Return a copy
    }

    public Map<String, ApiKey> getSmppCredentials() {
        return new HashMap<>(smppCredentials);
    }

    public Map<String, ApiKey> getAdminKeys() {
        return new HashMap<>(adminKeys);
    }

    public synchronized void updateApiKeys(List<ApiKey> newKeys) throws IOException {
        if (newKeys == null) {
            Log.warn("Attempted to update API key with null map. Ignoring.");
            return;
        }
        try {
            final var confFilePath = Paths.get(keysConfigPath);
            objectMapper
                    .writerWithDefaultPrettyPrinter()
                    .writeValue(confFilePath.toFile(), newKeys);
            // Reload the keys into memory
            loadApiKeys();
            Log.infof("Successfully updated and reloaded API keys in %s", keysConfigPath);
        } catch (Exception e) {
            Log.errorf(e, "Failed to update API keys file %s", keysConfigPath);
            throw new IOException("Failed to update API keys: " + e.getMessage(), e);
        }
    }

    public enum ApiKeyType {
        MESSAGE,
        ADMIN,
        SMPP;

        @JsonValue
        public String getValue() {
            return name().toLowerCase();
        }

        @JsonCreator
        public static ApiKeyType forValue(String value) {
            return Arrays.stream(values())
                    .filter(t -> t.name().equalsIgnoreCase(value))
                    .findFirst()
                    .orElse(MESSAGE);
        }
    }

    public record ApiKey(ApiKeyType type, String key, String systemId, String password) {
        public static ApiKey admin(String key) {
            return new ApiKey(ApiKeyType.ADMIN, key, null, null);
        }

        public static ApiKey message(String key) {
            return new ApiKey(ApiKeyType.MESSAGE, key, null, null);
        }

        public static ApiKey smpp(String systemId, String password) {
            return new ApiKey(ApiKeyType.SMPP, null, systemId, password);
        }
    }
}
