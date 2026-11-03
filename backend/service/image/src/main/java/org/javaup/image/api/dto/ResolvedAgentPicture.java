package org.javaup.image.api.dto;

import java.net.URI;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** annotation/viewState 可冻结到 AI 快照；originalUrl 仅供本次读取，不能持久化。 */
public record ResolvedAgentPicture(
        UUID pictureId,
        String sourceType,
        String mediaType,
        Integer width,
        Integer height,
        JsonNode viewState,
        JsonNode annotation,
        URI originalUrl
) {}
