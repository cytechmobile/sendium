package smsgateway.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

public class MockHttpServerResource implements QuarkusTestResourceLifecycleManager {

    private WireMockServer wireMockServer;

    @Override
    public Map<String, String> start() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        // You could return properties to be injected into Quarkus config, e.g.,
        // return Map.of("some.service.url", wireMockServer.baseUrl());
        // For now, tests can inject this resource and call getWireMockServer().
        return Map.of("mock.http.server.url", wireMockServer.baseUrl());
    }

    @Override
    public void stop() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    public WireMockServer getWireMockServer() {
        return wireMockServer;
    }

    // Optional: If tests need to inject the server directly
    @Override
    public void inject(TestInjector testInjector) {
        testInjector.injectIntoFields(
                wireMockServer, // Source of the value to inject
                new TestInjector.AnnotatedAndMatchesType(
                        InjectMockHttpServer.class, WireMockServer.class));
    }
}
