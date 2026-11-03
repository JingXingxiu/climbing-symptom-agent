package org.javaup.image.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** AI 一次读取当前 Assessment 使用的图片，避免每张图分别请求元数据与标注。 */
public record ResolveAgentPicturesRequest(
        @NotNull UUID assessmentId,
        @NotNull @Size(max = 12) List<@NotNull UUID> pictureIds
) {}
