package org.javaup.ai.model;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/** 输入中只有当前分支历史；图片 URL 在调用前刷新，不写回 Checkpoint。 */
public record AgentModelRequest(
        UUID sessionId,
        String provider,
        String model,
        String locale,
        JsonNode inputSnapshot,
        JsonNode state,
        List<Turn> history,
        List<Picture> pictures
) {

    public record Turn(
            String type,
            String message,
            JsonNode answer
    ) {}

    public record Picture(
            UUID pictureId,
            URI url,
            String mediaType
    ) {}
}
