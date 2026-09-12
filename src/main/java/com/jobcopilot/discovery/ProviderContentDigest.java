package com.jobcopilot.discovery;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
final class ProviderContentDigest {
    private static final String VERSION = "lever-content-v1";

    String calculate(String title, String location, String description, String hostedUrl, String applyUrl) {
        return VERSION + ":sha256:"
                + hash(VERSION, Arrays.asList(title, location, description, hostedUrl, applyUrl));
    }

    static String hash(String version, List<String> fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            put(digest, version);
            for (String field : fields) put(digest, field);
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void put(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
