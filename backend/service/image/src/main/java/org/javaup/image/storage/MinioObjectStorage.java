package org.javaup.image.storage;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.InputStream;
import org.springframework.stereotype.Component;

/** Streams objects to an existing MinIO bucket; the caller owns the input stream. */
@Component
public final class MinioObjectStorage {

    private final MinioClient minioClient;

    public MinioObjectStorage(MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    public void put(
            String bucketName,
            String objectKey,
            InputStream input,
            long sizeBytes,
            String contentType
    ) {
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalArgumentException("bucketName must not be blank");
        }
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("objectKey must not be blank");
        }
        if (input == null) {
            throw new IllegalArgumentException("input must not be null");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("contentType must not be blank");
        }

        PutObjectArgs arguments = PutObjectArgs.builder()
                .bucket(bucketName)
                .object(objectKey)
                .stream(input, sizeBytes, -1)
                .contentType(contentType)
                .build();

        try {
            minioClient.putObject(arguments);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to upload an object to MinIO", exception);
        }
    }
}
