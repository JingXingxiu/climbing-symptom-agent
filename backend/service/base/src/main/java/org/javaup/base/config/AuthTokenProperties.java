package org.javaup.base.config;

import java.time.Duration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Access-token settings; signing credentials deliberately have no shared default. */
@Setter
@Getter
@Component
@ConfigurationProperties(prefix = "base.auth")
public class AuthTokenProperties {

    private String secret;
    private String issuer = "climbing-base-service";
    private String audience = "climbing-api";
    private Duration ttl = Duration.ofHours(2);

}
