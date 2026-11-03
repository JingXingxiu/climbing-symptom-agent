package org.javaup.history.api.dto;

import java.net.URI;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** History 自己拥有的结果来源快照，不访问 AI 知识库，也不建立跨库外键。 */
public record ResultReference(
        @NotBlank @Size(max = 300) String title,
        @Size(max = 2048) String url,
        @Size(max = 2500) String excerpt,
        @NotBlank @Pattern(regexp = "WEB_SEARCH|KNOWLEDGE") String sourceType,
        UUID documentId,
        UUID chunkId,
        @Size(max = 120) String contentVersion
) {
    @AssertTrue(message = "Knowledge references need document/chunk IDs; web references need a public HTTP URL")
    @JsonIgnore
    public boolean isProvenanceValid() {
        if (url != null && !publicHttpUrl(url)) {
            return false;
        }
        return "KNOWLEDGE".equals(sourceType)
                ? documentId != null && chunkId != null
                : "WEB_SEARCH".equals(sourceType) && url != null;
    }

    private static boolean publicHttpUrl(String url) {
        try {
            URI uri = URI.create(url);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
