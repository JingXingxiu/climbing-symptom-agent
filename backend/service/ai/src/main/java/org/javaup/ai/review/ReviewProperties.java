package org.javaup.ai.review;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Review roles are selected by backend configuration, never by user-generated output. */
@Getter
@Setter
@ConfigurationProperties("ai.review")
public class ReviewProperties {
    private boolean enabled;
    private String provider;
    private String model;
}
