package topview.fileloader.service;

import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in smoke test; never makes uploads or changes server state. */
class SchoolTlsCompatibilityTest {
    @Test
    @EnabledIfSystemProperty(named = "fileloader.liveTest", matches = "true")
    void schoolGatewayCompletesTlsHandshake() throws Exception {
        try (Response response = DesktopApiClient.execute(
                DesktopApiClient.requestBuilder("batch/batchRecords", false).get().build())) {
            assertTrue(response.code() > 0, "server must return an HTTP response after TLS handshake");
        }
    }
}
