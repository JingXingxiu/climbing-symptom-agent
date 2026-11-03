package org.javaup.base.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;
import org.javaup.base.api.problem.ApiException;
import org.javaup.base.config.AuthTokenProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Issues server-signed bearer tokens whose subject is the internal account UUID. */
@Service
public class AccessTokenService {

    private final AuthTokenProperties properties;
    private final Clock clock;

    @Autowired
    public AccessTokenService(AuthTokenProperties properties) {
        this(properties, Clock.systemUTC());
    }

    AccessTokenService(AuthTokenProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Call before exchanging a one-use provider code or writing an account. */
    public void requireConfigured() {
        if (properties.getSecret() == null || properties.getSecret().isBlank()
                || properties.getSecret().getBytes(StandardCharsets.UTF_8).length < 32
                || properties.getTtl() == null || properties.getTtl().isNegative()
                || properties.getTtl().getSeconds() < 1
                || properties.getIssuer() == null || properties.getIssuer().isBlank()
                || properties.getAudience() == null || properties.getAudience().isBlank()) {
            throw notConfigured();
        }
        try {
            Date.from(clock.instant().plusSeconds(properties.getTtl().getSeconds()));
        } catch (RuntimeException exception) {
            throw notConfigured();
        }
    }

    public IssuedToken issue(UUID userId) {
        requireConfigured();
        Objects.requireNonNull(userId, "userId");
        long expiresInSeconds = properties.getTtl().getSeconds();
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .issuer(properties.getIssuer())
                .audience(properties.getAudience())
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(issuedAt.plusSeconds(expiresInSeconds)))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
                .type(JOSEObjectType.JWT).build(), claims);
        try {
            jwt.sign(new MACSigner(properties.getSecret().getBytes(StandardCharsets.UTF_8)));
        } catch (JOSEException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_TOKEN_UNAVAILABLE",
                    "Access-token signing is temporarily unavailable.");
        }
        return new IssuedToken(jwt.serialize(), expiresInSeconds);
    }

    private static ApiException notConfigured() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_NOT_CONFIGURED",
                "Access-token signing is not configured.");
    }

    public record IssuedToken(String accessToken, long expiresInSeconds) {}
}
