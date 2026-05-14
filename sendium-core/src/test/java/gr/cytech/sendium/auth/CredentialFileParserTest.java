package gr.cytech.sendium.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CredentialFileParserTest {

    @TempDir
    Path tempDir;

    @Test
    void loadAndParse_SingleSmppCredential() throws Exception {
        String yaml = """
                credentials:
                  - type: SMPP
                    accountId: "account-123"
                    product: "TestProduct"
                    systemId: "sysId123"
                    password: "secretPass"
                    allowedIps:
                      - "192.168.1.1"
                      - "10.0.0.1"
                """;
        Path file = tempDir.resolve("credentials.yml");
        Files.writeString(file, yaml);

        Map<String, CredentialFileWatcher.Credential> result = CredentialFileParser.loadAndParse(file);

        assertEquals(1, result.size());
        CredentialFileWatcher.Credential cred = result.get("sysId123");
        assertNotNull(cred);
        assertEquals(CredentialFileWatcher.CredentialType.SMPP, cred.type());
        assertEquals("account-123", cred.accountId());
        assertEquals("TestProduct", cred.product());
        assertEquals("sysId123", cred.systemId());
        assertEquals("secretPass", cred.password());
        assertTrue(cred.allowedIps().contains("192.168.1.1"));
        assertTrue(cred.allowedIps().contains("10.0.0.1"));
    }

    @Test
    void loadAndParse_SingleHttpCredential() throws Exception {
        String yaml = """
                credentials:
                  - type: HTTP
                    accountId: "http-account"
                    apiKey: "apiKey456"
                """;
        Path file = tempDir.resolve("credentials.yml");
        Files.writeString(file, yaml);

        Map<String, CredentialFileWatcher.Credential> result = CredentialFileParser.loadAndParse(file);

        assertEquals(1, result.size());
        CredentialFileWatcher.Credential cred = result.get("apiKey456");
        assertNotNull(cred);
        assertEquals(CredentialFileWatcher.CredentialType.HTTP, cred.type());
        assertEquals("http-account", cred.accountId());
        assertEquals("apiKey456", cred.apiKey());
    }

    @Test
    void loadAndParse_MultipleCredentials() throws Exception {
        String yaml = """
                credentials:
                  - type: SMPP
                    systemId: "sysId1"
                    password: "pass1"
                  - type: HTTP
                    apiKey: "apiKey1"
                  - type: SMPP
                    systemId: "sysId2"
                    password: "pass2"
                """;
        Path file = tempDir.resolve("credentials.yml");
        Files.writeString(file, yaml);

        Map<String, CredentialFileWatcher.Credential> result = CredentialFileParser.loadAndParse(file);

        assertEquals(3, result.size());
        assertTrue(result.containsKey("sysId1"));
        assertTrue(result.containsKey("sysId2"));
        assertTrue(result.containsKey("apiKey1"));
    }

    @Test
    void loadAndParse_InvalidCredential_FilteredOut() throws Exception {
        String yaml = """
                credentials:
                  - type: SMPP
                    systemId: "validSysId"
                    password: "validPass"
                  - type: SMPP
                    systemId: null
                    password: null
                  - type: HTTP
                    apiKey: null
                """;
        Path file = tempDir.resolve("credentials.yml");
        Files.writeString(file, yaml);

        Map<String, CredentialFileWatcher.Credential> result = CredentialFileParser.loadAndParse(file);

        assertEquals(1, result.size());
        assertTrue(result.containsKey("validSysId"));
    }

    @Test
    void loadAndParse_EmptyFile_ThrowsException() {
        Path file = tempDir.resolve("credentials.yml");
        assertThrows(Exception.class, () -> CredentialFileParser.loadAndParse(file));
    }

    @Test
    void loadAndParse_NoCredentialsKey() throws Exception {
        String yaml = """
                otherKey: value
                """;
        Path file = tempDir.resolve("credentials.yml");
        Files.writeString(file, yaml);

        Map<String, CredentialFileWatcher.Credential> result = CredentialFileParser.loadAndParse(file);

        assertTrue(result.isEmpty());
    }

    @Test
    void loadAndParse_DuplicateSystemIds_LatestWins() throws Exception {
        String yaml = """
                credentials:
                  - type: SMPP
                    systemId: "sysId1"
                    password: "firstPass"
                    product: "FirstProduct"
                  - type: SMPP
                    systemId: "sysId1"
                    password: "lastPass"
                    product: "LastProduct"
                """;
        Path file = tempDir.resolve("credentials.yml");
        Files.writeString(file, yaml);

        Map<String, CredentialFileWatcher.Credential> result = CredentialFileParser.loadAndParse(file);

        assertEquals(1, result.size());
        CredentialFileWatcher.Credential cred = result.get("sysId1");
        assertEquals("lastPass", cred.password());
        assertEquals("LastProduct", cred.product());
    }

    @Test
    void loadAndParse_DuplicateApiKeys_LatestWins() throws Exception {
        String yaml = """
                credentials:
                  - type: HTTP
                    apiKey: "apiKey1"
                    accountId: "firstAccount"
                  - type: HTTP
                    apiKey: "apiKey1"
                    accountId: "lastAccount"
                """;
        Path file = tempDir.resolve("credentials.yml");
        Files.writeString(file, yaml);

        Map<String, CredentialFileWatcher.Credential> result = CredentialFileParser.loadAndParse(file);

        assertEquals(1, result.size());
        CredentialFileWatcher.Credential cred = result.get("apiKey1");
        assertEquals("lastAccount", cred.accountId());
    }

    @Test
    void loadAndParse_IgnoreUnknownFields() throws Exception {
        String yaml = """
                credentials:
                  - type: SMPP
                    systemId: "sysId1"
                    password: "pass1"
                    unknownField: "ignored"
                    anotherUnknown: 123
                """;
        Path file = tempDir.resolve("credentials.yml");
        Files.writeString(file, yaml);

        Map<String, CredentialFileWatcher.Credential> result = CredentialFileParser.loadAndParse(file);

        assertEquals(1, result.size());
        assertTrue(result.containsKey("sysId1"));
    }

    @Test
    void loadAndParse_NonExistentFile_ThrowsException() {
        Path file = tempDir.resolve("nonexistent.yml");

        assertThrows(Exception.class, () -> CredentialFileParser.loadAndParse(file));
    }
}
