package org.javaup.gateway.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.javaup.gateway.config.GatewayAuthProperties;
import org.junit.jupiter.api.Test;

class JwtTokenVerifierTest {

    private static final String SECRET = "test-only-shared-signing-secret-at-least-32-bytes";
    private static final String OTHER_SECRET = "another-test-signing-secret-at-least-32-bytes";
    private static final UUID USER_ID = UUID.fromString("203c3414-d3fe-4a63-9373-b2063d7f067b");
    private static final Instant NOW = Instant.parse("2026-10-26T04:05:06Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void verifiesBaseServiceClaimsAndReturnsTheInternalUserId() throws Exception {
        JWTClaimsSet claims = validClaims().jwtID(UUID.randomUUID().toString()).build();

        assertEquals(USER_ID, verifier().verify(sign(claims)));
    }

    @Test
    void acceptsTokensWithoutJtiAndAnAudienceListContainingTheConfiguredAudience() throws Exception {
        JWTClaimsSet claims = validClaims().audience(List.of("another-api", "climbing-api")).build();

        assertEquals(USER_ID, verifier().verify(sign(claims)));
    }

    @Test
    void acceptsConfiguredIssuerAudienceAndUtf8SigningSecret() throws Exception {
        GatewayAuthProperties properties = configured();
        properties.setSecret("密".repeat(11));
        properties.setIssuer("deployment-issuer");
        properties.setAudience("deployment-api");
        JWTClaimsSet claims = validClaims().issuer("deployment-issuer").audience("deployment-api").build();

        assertEquals(USER_ID, new JwtTokenVerifier(properties, CLOCK)
                .verify(sign(claims, properties.getSecret(), JWSAlgorithm.HS256)));
    }

    @Test
    void acceptsExpiryWithinTheDefaultLeewayButRejectsItsBoundary() throws Exception {
        assertEquals(USER_ID, verifier().verify(sign(validClaims()
                .expirationTime(Date.from(NOW.minusSeconds(29))).build())));
        assertInvalid(sign(validClaims().expirationTime(Date.from(NOW.minusSeconds(30))).build()));
        assertInvalid(sign(validClaims().expirationTime(Date.from(NOW.minusSeconds(31))).build()));
    }

    @Test
    void zeroLeewayRequiresTheTokenToBeUnexpiredAtTheCurrentInstant() throws Exception {
        GatewayAuthProperties properties = configured();
        properties.setClockSkew(Duration.ZERO);
        JwtTokenVerifier verifier = new JwtTokenVerifier(properties, CLOCK);

        assertEquals(USER_ID, verifier.verify(sign(validClaims().expirationTime(Date.from(NOW.plusSeconds(1))).build())));
        assertThrows(IllegalArgumentException.class,
                () -> verifier.verify(sign(validClaims().expirationTime(Date.from(NOW)).build())));
    }

    @Test
    void rejectsAValidSignatureFromAnotherKey() throws Exception {
        assertInvalid(sign(validClaims().build(), OTHER_SECRET, JWSAlgorithm.HS256));
    }

    @Test
    void rejectsMissingOrIncorrectIssuerAndAudience() throws Exception {
        assertInvalid(sign(validClaims().issuer("another-issuer").build()));
        assertInvalid(sign(validClaims().issuer(null).build()));
        assertInvalid(sign(validClaims().audience("another-api").build()));
        assertInvalid(sign(validClaims().audience((String) null).build()));
    }

    @Test
    void rejectsMissingExpirationIssueTimeAndSubject() throws Exception {
        assertInvalid(sign(validClaims().expirationTime(null).build()));
        assertInvalid(sign(validClaims().issueTime(null).build()));
        assertInvalid(sign(validClaims().subject(null).build()));
    }

    @Test
    void rejectsExpirationAtOrBeforeIssueTimeEvenWithinLeeway() throws Exception {
        assertInvalid(sign(validClaims().issueTime(Date.from(NOW)).expirationTime(Date.from(NOW)).build()));
        assertInvalid(sign(validClaims().issueTime(Date.from(NOW)).expirationTime(Date.from(NOW.minusSeconds(1))).build()));
    }

    @Test
    void rejectsFutureIssuedAtAndNotBeforeBeyondTheLeeway() throws Exception {
        assertInvalid(sign(validClaims().issueTime(Date.from(NOW.plusSeconds(31))).build()));
        assertInvalid(sign(validClaims().notBeforeTime(Date.from(NOW.plusSeconds(31))).build()));
        assertEquals(USER_ID, verifier().verify(sign(validClaims()
                .issueTime(Date.from(NOW.plusSeconds(30)))
                .notBeforeTime(Date.from(NOW.plusSeconds(30))).build())));
    }

    @Test
    void rejectsMalformedAndNonCanonicalUuidSubjects() throws Exception {
        for (String subject : List.of("", "openid-provider-identifier", "1-1-1-1-1", USER_ID.toString().toUpperCase())) {
            assertInvalid(sign(validClaims().subject(subject).build()));
        }
    }

    @Test
    void rejectsAlgorithmsOtherThanHs256AndUnsignedTokens() throws Exception {
        String longSecret = "x".repeat(64);
        GatewayAuthProperties properties = configured();
        properties.setSecret(longSecret);
        JwtTokenVerifier verifier = new JwtTokenVerifier(properties, CLOCK);

        assertThrows(IllegalArgumentException.class,
                () -> verifier.verify(sign(validClaims().build(), longSecret, JWSAlgorithm.HS384)));
        assertThrows(IllegalArgumentException.class,
                () -> verifier.verify(sign(validClaims().build(), longSecret, JWSAlgorithm.HS512)));
        assertInvalid(new PlainJWT(validClaims().build()).serialize());
    }

    @Test
    void rejectsMalformedTokensAndClaimTypesWithoutRetainingTheirContents() throws Exception {
        for (String token : List.of("", " ", "sensitive-token-value", "eyJhbGciOiJIUzI1NiJ9.invalid.invalid")) {
            assertInvalid(token);
        }
        assertThrows(IllegalArgumentException.class, () -> verifier().verify(null));
        assertInvalid(sign(validClaims().claim("sub", 42).build()));
        assertInvalid(sign(validClaims().claim("exp", "tomorrow").build()));
    }

    @Test
    void configurationIsCheckedOnVerificationSoPublicEndpointsCanStartWithoutASecret() {
        GatewayAuthProperties properties = new GatewayAuthProperties();
        JwtTokenVerifier verifier = new JwtTokenVerifier(properties, CLOCK);

        assertNull(properties.getSecret());
        assertEquals(Duration.ofSeconds(30), properties.getClockSkew());
        assertThrows(IllegalStateException.class, () -> verifier.verify("anything"));
        for (String secret : List.of("", "short-secret", " ".repeat(32))) {
            properties.setSecret(secret);
            assertThrows(IllegalStateException.class, () -> verifier.verify("anything"));
        }
    }

    @Test
    void rejectsMissingIssuerAudienceAndInvalidClockSkew() {
        GatewayAuthProperties properties = configured();
        JwtTokenVerifier verifier = new JwtTokenVerifier(properties, CLOCK);

        properties.setIssuer(null);
        assertThrows(IllegalStateException.class, () -> verifier.verify("anything"));
        properties.setIssuer(" ");
        assertThrows(IllegalStateException.class, () -> verifier.verify("anything"));
        properties.setIssuer("climbing-base-service");
        properties.setAudience(null);
        assertThrows(IllegalStateException.class, () -> verifier.verify("anything"));
        properties.setAudience(" ");
        assertThrows(IllegalStateException.class, () -> verifier.verify("anything"));
        properties.setAudience("climbing-api");
        for (Duration skew : new Duration[]{null, Duration.ofNanos(-1), Duration.ofMinutes(5).plusNanos(1)}) {
            properties.setClockSkew(skew);
            assertThrows(IllegalStateException.class, () -> verifier.verify("anything"));
        }
    }

    private static GatewayAuthProperties configured() {
        GatewayAuthProperties properties = new GatewayAuthProperties();
        properties.setSecret(SECRET);
        return properties;
    }

    private static JwtTokenVerifier verifier() {
        return new JwtTokenVerifier(configured(), CLOCK);
    }

    private static JWTClaimsSet.Builder validClaims() {
        return new JWTClaimsSet.Builder()
                .subject(USER_ID.toString())
                .issuer("climbing-base-service")
                .audience("climbing-api")
                .issueTime(Date.from(NOW.minusSeconds(60)))
                .expirationTime(Date.from(NOW.plusSeconds(3600)));
    }

    private static String sign(JWTClaimsSet claims) throws Exception {
        return sign(claims, SECRET, JWSAlgorithm.HS256);
    }

    private static String sign(JWTClaimsSet claims, String secret, JWSAlgorithm algorithm) throws Exception {
        SignedJWT token = new SignedJWT(new JWSHeader.Builder(algorithm).type(JOSEObjectType.JWT).build(), claims);
        token.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return token.serialize();
    }

    private static void assertInvalid(String token) {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> verifier().verify(token));
        assertEquals("Invalid access token.", exception.getMessage());
        assertFalse(exception.toString().contains(SECRET));
        assertNull(exception.getCause());
        if (!token.isBlank()) {
            assertFalse(exception.toString().contains(token));
        }
    }
}
