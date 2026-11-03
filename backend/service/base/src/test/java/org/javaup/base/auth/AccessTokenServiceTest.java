package org.javaup.base.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.javaup.base.api.problem.ApiException;
import org.javaup.base.config.AuthTokenProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AccessTokenServiceTest {

    private static final String SECRET = "test-only-signing-secret-at-least-32-bytes";
    private static final Instant NOW = Instant.parse("2026-10-26T04:05:06Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void missingAndShortSigningSecretsFailOnlyWhenLoginRequiresThem() {
        AuthTokenProperties properties = new AuthTokenProperties();
        AccessTokenService service = new AccessTokenService(properties, CLOCK);

        assertNotConfigured(assertThrows(ApiException.class, service::requireConfigured));
        assertNotConfigured(assertThrows(ApiException.class, () -> service.issue(UUID.randomUUID())));
        properties.setSecret("short-secret");
        assertNotConfigured(assertThrows(ApiException.class, service::requireConfigured));
        properties.setSecret(" ".repeat(32));
        assertNotConfigured(assertThrows(ApiException.class, service::requireConfigured));
    }

    @Test
    void nonPositiveAndUnusableTtlsAreRejectedBeforeIssuingTokens() {
        AuthTokenProperties properties = configured();
        AccessTokenService service = new AccessTokenService(properties, CLOCK);

        for (Duration ttl : new Duration[]{Duration.ZERO, Duration.ofSeconds(-1),
                Duration.ofNanos(1), Duration.ofSeconds(Long.MAX_VALUE)}) {
            properties.setTtl(ttl);
            assertNotConfigured(assertThrows(ApiException.class, service::requireConfigured));
        }
    }

    @Test
    void issuesVerifiableHs256JwtWithInternalUuidAndDefaultClaims() throws Exception {
        AuthTokenProperties properties = configured();
        AccessTokenService service = new AccessTokenService(properties, CLOCK);
        UUID userId = UUID.randomUUID();

        AccessTokenService.IssuedToken token = service.issue(userId);
        SignedJWT jwt = SignedJWT.parse(token.accessToken());
        JWTClaimsSet claims = jwt.getJWTClaimsSet();

        assertTrue(jwt.verify(new MACVerifier(SECRET.getBytes(StandardCharsets.UTF_8))));
        assertFalse(jwt.verify(new MACVerifier("another-test-signing-secret-with-32-bytes"
                .getBytes(StandardCharsets.UTF_8))));
        assertEquals(JWSAlgorithm.HS256, jwt.getHeader().getAlgorithm());
        assertEquals(JOSEObjectType.JWT, jwt.getHeader().getType());
        assertEquals(userId.toString(), claims.getSubject());
        assertEquals("climbing-base-service", claims.getIssuer());
        assertEquals(List.of("climbing-api"), claims.getAudience());
        assertEquals(Date.from(NOW), claims.getIssueTime());
        assertEquals(Date.from(NOW.plus(Duration.ofHours(2))), claims.getExpirationTime());
        assertEquals(7200, token.expiresInSeconds());
        assertNotNull(UUID.fromString(claims.getJWTID()));
        assertEquals(6, claims.getClaims().size());
        assertFalse(jwt.getPayload().toString().contains(SECRET));
    }

    @Test
    void configuredClaimsAndTtlAreUsedAndEachTokenHasUniqueId() throws Exception {
        AuthTokenProperties properties = configured();
        properties.setIssuer("deployment-issuer");
        properties.setAudience("deployment-api");
        properties.setTtl(Duration.ofMinutes(15));
        AccessTokenService service = new AccessTokenService(properties, CLOCK);
        UUID userId = UUID.randomUUID();

        AccessTokenService.IssuedToken first = service.issue(userId);
        AccessTokenService.IssuedToken second = service.issue(userId);
        JWTClaimsSet claims = SignedJWT.parse(first.accessToken()).getJWTClaimsSet();

        assertEquals("deployment-issuer", claims.getIssuer());
        assertEquals(List.of("deployment-api"), claims.getAudience());
        assertEquals(Date.from(NOW.plusSeconds(900)), claims.getExpirationTime());
        assertEquals(900, first.expiresInSeconds());
        assertTrue(claims.getExpirationTime().after(Date.from(NOW.plusSeconds(899))));
        assertFalse(claims.getExpirationTime().after(Date.from(NOW.plusSeconds(900))));
        assertNotEquals(claims.getJWTID(), SignedJWT.parse(second.accessToken()).getJWTClaimsSet().getJWTID());
    }

    @Test
    void signingKeyMinimumIsMeasuredInUtf8Bytes() throws Exception {
        AuthTokenProperties properties = configured();
        properties.setSecret("密".repeat(11));

        var token = new AccessTokenService(properties, CLOCK).issue(UUID.randomUUID());

        assertTrue(SignedJWT.parse(token.accessToken()).verify(
                new MACVerifier(properties.getSecret().getBytes(StandardCharsets.UTF_8))));
    }

    private static AuthTokenProperties configured() {
        AuthTokenProperties properties = new AuthTokenProperties();
        properties.setSecret(SECRET);
        return properties;
    }

    private static void assertNotConfigured(ApiException exception) {
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.status());
        assertEquals("AUTH_NOT_CONFIGURED", exception.code());
        assertFalse(exception.toString().contains(SECRET));
    }
}
