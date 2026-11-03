package org.javaup.image.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MinioObjectStorageTest {

    @Test
    void passesObjectMetadataAndKnownSizeWithoutReadingOrClosingTheStream() throws Exception {
        MinioClient client = mock(MinioClient.class);
        TrackingInputStream input = new TrackingInputStream("picture".getBytes(StandardCharsets.UTF_8));

        new MinioObjectStorage(client).put(
                "picture-bucket", "users/user-id/original.jpg", input, 7, "image/jpeg"
        );

        ArgumentCaptor<PutObjectArgs> arguments = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(client).putObject(arguments.capture());
        assertEquals("picture-bucket", arguments.getValue().bucket());
        assertEquals("users/user-id/original.jpg", arguments.getValue().object());
        assertSame(input, arguments.getValue().stream());
        assertEquals(7, arguments.getValue().objectSize());
        assertEquals("image/jpeg", arguments.getValue().contentType());
        assertEquals(7, input.available());
        assertFalse(input.closed);
    }

    @Test
    void rejectsInvalidInputBeforeCallingMinio() {
        MinioClient client = mock(MinioClient.class);
        MinioObjectStorage storage = new MinioObjectStorage(client);
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[0]);

        assertThrows(IllegalArgumentException.class,
                () -> storage.put(null, "object.jpg", input, 0, "image/jpeg"));
        assertThrows(IllegalArgumentException.class,
                () -> storage.put(" ", "object.jpg", input, 0, "image/jpeg"));
        assertThrows(IllegalArgumentException.class,
                () -> storage.put("ab", "object.jpg", input, 0, "image/jpeg"));
        assertThrows(IllegalArgumentException.class,
                () -> storage.put("picture-bucket", null, input, 0, "image/jpeg"));
        assertThrows(IllegalArgumentException.class,
                () -> storage.put("picture-bucket", " ", input, 0, "image/jpeg"));
        assertThrows(IllegalArgumentException.class,
                () -> storage.put("picture-bucket", "object.jpg", null, 0, "image/jpeg"));
        assertThrows(IllegalArgumentException.class,
                () -> storage.put("picture-bucket", "object.jpg", input, -1, "image/jpeg"));
        assertThrows(IllegalArgumentException.class,
                () -> storage.put("picture-bucket", "object.jpg", input, 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> storage.put("picture-bucket", "object.jpg", input, 0, " "));
        verifyNoInteractions(client);
    }

    @Test
    void retainsTheSdkFailureCauseAndLeavesStreamOpen() throws Exception {
        MinioClient client = mock(MinioClient.class);
        IOException failure = new IOException("storage unavailable");
        when(client.putObject(any(PutObjectArgs.class))).thenThrow(failure);
        TrackingInputStream input = new TrackingInputStream(new byte[] {1});

        IllegalStateException result = assertThrows(IllegalStateException.class,
                () -> new MinioObjectStorage(client).put(
                        "picture-bucket", "object.jpg", input, 1, "image/jpeg"
                ));

        assertSame(failure, result.getCause());
        assertFalse(input.closed);
    }

    private static final class TrackingInputStream extends BufferedInputStream {

        private boolean closed;

        private TrackingInputStream(byte[] content) {
            super(new ByteArrayInputStream(content));
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
