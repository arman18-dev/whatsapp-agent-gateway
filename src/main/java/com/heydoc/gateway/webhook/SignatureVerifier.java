package com.heydoc.gateway.webhook;

import com.heydoc.gateway.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Checks the X-Hub-Signature-256 header: {@code sha256=} + hex(HMAC-SHA256(app secret, raw body)).
 * Fails closed when no secret is configured.
 */
@Component
@Profile("gateway")
public class SignatureVerifier {

    private static final String PREFIX = "sha256=";
    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    public SignatureVerifier(AppProperties properties) {
        String configured = properties.whatsappAppSecret();
        this.secret = (configured == null || configured.isBlank())
                ? new byte[0]
                : configured.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isValid(byte[] rawBody, String headerValue) {
        if (secret.length == 0 || headerValue == null) {
            return false;
        }
        byte[] expected = (PREFIX + HexFormat.of().formatHex(hmac(rawBody)))
                .getBytes(StandardCharsets.UTF_8);
        // Constant-time comparison.
        return MessageDigest.isEqual(expected, headerValue.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] hmac(byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
