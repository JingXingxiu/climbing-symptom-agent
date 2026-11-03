package org.javaup.base.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Transport DTO for {@code POST /api/base/v1/auth/wechat-login}.
 */
public record WechatLoginRequest(
        @NotBlank @Size(max = 256) String code,
        @Size(max = 60) String displayName,
        @Size(max = 1000) String avatarUrl
) {
}
