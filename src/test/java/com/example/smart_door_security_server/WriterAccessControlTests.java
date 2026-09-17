package com.example.smart_door_security_server;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class WriterAccessControlTests {
    @Test void environmentWhitespaceIsNormalizedLikeBothPythonClients() {
        String token = "valid-token-with-at-least-32-characters";
        WriterAccessControl access = new WriterAccessControl("  " + token + "\n", " 1 ", "\n" + token + " ", "1");
        assertDoesNotThrow(() -> access.requireDevice("Bearer " + token, 1));
        assertDoesNotThrow(() -> access.requireReport("Bearer " + token, 1));
        assertEquals(401, assertThrows(ResponseStatusException.class,
                () -> access.requireDevice("Bearer wrong-token", 1)).getStatusCode().value());
    }

    @Test void absentTokenOrOwnerNeverFallsBackToAnotherAccount() {
        String device = "device-test-token-with-at-least-32-characters";
        String report = "report-test-token-with-at-least-32-characters";
        WriterAccessControl noDeviceToken = new WriterAccessControl("", "1", report, "1");
        WriterAccessControl noReportOwner = new WriterAccessControl(device, "1", report, "");
        WriterAccessControl invalidOwner = new WriterAccessControl(device, "not-a-number", report, "0");
        WriterAccessControl shortToken = new WriterAccessControl("short", "1", report, "1");
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> noDeviceToken.requireDevice("Bearer anything", 1)).getStatusCode().value());
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> noReportOwner.requireReport("Bearer " + report, 1)).getStatusCode().value());
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> invalidOwner.requireDevice("Bearer " + device, 1)).getStatusCode().value());
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> invalidOwner.requireReport("Bearer " + report, 1)).getStatusCode().value());
        assertEquals(503, assertThrows(ResponseStatusException.class, () -> shortToken.requireDevice("Bearer short", 1)).getStatusCode().value());
    }
}
