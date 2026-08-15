package com.ticketmind.backend.common.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param fieldKey base64-encoded 256-bit key for column-level encryption. Has
 *                 no default for the same reason {@code app.jwt.secret} has
 *                 none: a fallback key is worse than a failed startup, because
 *                 it encrypts production data under a value that is in the
 *                 source tree.
 */
@ConfigurationProperties(prefix = "app.crypto")
public record CryptoProperties(String fieldKey) {
}
