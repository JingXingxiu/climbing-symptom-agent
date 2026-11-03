package org.javaup.ai.client;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import tools.jackson.databind.JsonNode;

/** 图片批量读取只调用一次，不在每个 Picture 上分别发 Feign 请求。 */
@FeignClient(name = "image-service", contextId = "agentImageClient")
public interface AgentImageClient {

    @PostMapping("/internal/image/agent-pictures/resolve")
    List<PictureResponse> resolve(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestBody ResolveRequest request
    );

    record ResolveRequest(
            UUID assessmentId,
            List<UUID> pictureIds
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PictureResponse(
            UUID pictureId,
            String sourceType,
            String mediaType,
            Integer width,
            Integer height,
            JsonNode viewState,
            JsonNode annotation,
            URI originalUrl
    ) {
    }
}
