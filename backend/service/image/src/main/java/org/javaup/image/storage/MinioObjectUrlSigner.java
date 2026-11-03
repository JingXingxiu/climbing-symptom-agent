package org.javaup.image.storage;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import java.net.URI;
import org.javaup.image.config.MinioProperties;
import org.springframework.stereotype.Component;

/** Creates transient GET URLs without persisting or logging them. */
@Component
public final class MinioObjectUrlSigner {

    private final MinioClient minioClient;
    private final MinioProperties properties;

    public MinioObjectUrlSigner(
            MinioClient minioClient,
            MinioProperties properties
    ) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    public URI signGet(String bucketName, String objectKey) {
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalArgumentException("bucketName must not be blank");
        }
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey must not be blank");
        }

        try {
            String signedUrl = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucketName)
                            .object(objectKey)
                            .expiry(properties.presignedUrlExpirySeconds())
                            .build()
            );
            return URI.create(signedUrl);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to generate a MinIO presigned GET URL",
                    exception
            );
        }
    }
}
