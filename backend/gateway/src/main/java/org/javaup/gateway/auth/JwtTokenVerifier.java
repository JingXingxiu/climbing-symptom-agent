package org.javaup.gateway.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;
import org.javaup.gateway.config.GatewayAuthProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Verifies locally signed HS256 tokens and returns their internal account UUID. */
@Component
public class JwtTokenVerifier {

    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private final GatewayAuthProperties properties;
    private final Clock clock;

    @Autowired
    public JwtTokenVerifier(GatewayAuthProperties properties) {
        this(properties, Clock.systemUTC());
    }

    JwtTokenVerifier(GatewayAuthProperties properties, Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Configuration is checked here so public endpoints can run before credentials are set. */
    public UUID verify(String token) {
        VerificationSettings settings = configuredSettings();
        if (token == null || token.isBlank()) {
            throw invalidToken();
        }

        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())
                    || !jwt.verify(new MACVerifier(settings.secret()))) {
                throw invalidToken();
            }

            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!settings.issuer().equals(claims.getIssuer())
                    || claims.getAudience() == null || !claims.getAudience().contains(settings.audience())) {
                throw invalidToken();
            }

            Date issuedAt = claims.getIssueTime();
            Date expiresAt = claims.getExpirationTime();
            Date notBefore = claims.getNotBeforeTime();
            Instant now = clock.instant();
            if (issuedAt == null || expiresAt == null
                    || (claims.getClaims().containsKey("nbf") && notBefore == null)
                    || !expiresAt.after(issuedAt)
                    || !expiresAt.toInstant().isAfter(now.minus(settings.clockSkew()))
                    || issuedAt.toInstant().isAfter(now.plus(settings.clockSkew()))
                    || (notBefore != null && notBefore.toInstant().isAfter(now.plus(settings.clockSkew())))) {
                throw invalidToken();
            }

            String subject = claims.getSubject();
            if (subject == null) {
                throw invalidToken();
            }
            UUID userId = UUID.fromString(subject);
            if (!userId.toString().equals(subject)) {
                throw invalidToken();
            }
            return userId;
        } catch (ParseException | JOSEException | IllegalArgumentException exception) {
            // Parser exception details may include the original credential; never retain them.
            throw invalidToken();
        }
    }

    private VerificationSettings configuredSettings() {
        String secret = properties.getSecret();
        String issuer = properties.getIssuer();
        String audience = properties.getAudience();
        Duration clockSkew = properties.getClockSkew();
        if (secret == null || secret.isBlank() || secret.getBytes(StandardCharsets.UTF_8).length < 32
                || issuer == null || issuer.isBlank() || audience == null || audience.isBlank()
                || clockSkew == null || clockSkew.isNegative() || clockSkew.compareTo(MAX_CLOCK_SKEW) > 0) {
            throw new IllegalStateException("Access-token verification is not configured.");
        }
        return new VerificationSettings(secret.getBytes(StandardCharsets.UTF_8), issuer, audience, clockSkew);
    }

    private static IllegalArgumentException invalidToken() {
        return new IllegalArgumentException("Invalid access token.");
    }

    private record VerificationSettings(byte[] secret, String issuer, String audience, Duration clockSkew) {}
}
