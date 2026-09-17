package com.example.smart_door_security_server;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

final class RequestFingerprint {
    private RequestFingerprint() { }

    static String of(String... fields) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                for (String field : fields) {
                    if (field == null) { output.writeInt(-1); continue; }
                    byte[] value = field.getBytes(StandardCharsets.UTF_8);
                    output.writeInt(value.length);
                    output.write(value);
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to fingerprint request", ex);
        }
    }
}
