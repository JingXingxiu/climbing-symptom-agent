package org.javaup.image.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.minio.MinioClient;
import java.net.URI;
import org.javaup.image.config.MinioProperties;
import org.junit.jupiter.api.Test;

class MinioObjectUrlSignerTest {

    @Test
    void signsShortLivedGetUrlWithoutContactingMinio() {
        MinioProperties properties = new MinioProperties(
                "http://127.0.0.1:9000",
                "test-access-key",
                "test-secret-key",
                "us-east-1",
                "picture-bucket",
                600
        );
        MinioClient client = MinioClient.builder()
                .endpoint(properties.endpoint())
                .credentials(properties.accessKey(), properties.secretKey())
                .region(properties.region())
                .build();

        URI result = new MinioObjectUrlSigner(client, properties)
                .signGet("picture-bucket", "users/user-id/original.jpg");

        assertEquals("127.0.0.1", result.getHost());
        assertEquals("/picture-bucket/users/user-id/original.jpg", result.getPath());
        assertTrue(result.getRawQuery().contains("X-Amz-Expires=600"));
        assertTrue(result.getRawQuery().contains("X-Amz-Signature="));
    }
}
