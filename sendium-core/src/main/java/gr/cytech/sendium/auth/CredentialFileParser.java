package gr.cytech.sendium.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class CredentialFileParser {
    private static final Logger logger = LoggerFactory.getLogger(CredentialFileParser.class);

    private static final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    public static Map<String, CredentialFileWatcher.Credential> loadAndParse(Path filePath) throws Exception {
        CredentialWrapper wrapper = mapper.readValue(filePath.toFile(), CredentialWrapper.class);

        if (wrapper == null || wrapper.credentials() == null) {
            logger.warn("Credential file is empty or formatted incorrectly.");
            return Map.of();
        }

        // Convert the List into a Map where the key is the systemId (SMPP) or apiKey(fallback to systemId)  (HTTP)
        return wrapper.credentials().stream()
                .filter(CredentialFileWatcher.Credential::isValid)
                .collect(Collectors.toMap(
                        CredentialFileWatcher.Credential::getLookupKey,
                        cred -> cred,
                        (existing, replacement) -> {
                            // Handle cases where the operator accidentally copy-pasted the same systemId or apiKey twice
                            logger.warn("Duplicate credential key found. Overwriting with the latest entry.");
                            return replacement;
                        }
                ));
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CredentialWrapper(List<CredentialFileWatcher.Credential> credentials) {}
}