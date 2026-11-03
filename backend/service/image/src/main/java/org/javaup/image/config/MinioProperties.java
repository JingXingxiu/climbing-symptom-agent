package org.javaup.image.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Configuration for MinIO picture storage and short-lived download URLs. */
@Validated
@ConfigurationProperties(prefix = "image.storage.minio")
public record MinioProperties(
        @NotBlank String endpoint,
        @NotBlank String accessKey,
        @NotBlank String secretKey,
        @NotBlank String region,
        @NotBlank String bucket,
        @Min(1) @Max(604800) int presignedUrlExpirySeconds
) {
}
