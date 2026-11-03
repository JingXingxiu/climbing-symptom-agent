package org.javaup.base.api;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import org.javaup.base.api.dto.AuthSessionResponse;
import org.javaup.base.api.dto.UpdateUserProfileRequest;
import org.javaup.base.api.dto.UserProfileResponse;
import org.javaup.base.api.dto.WechatLoginRequest;
import org.javaup.base.service.UserAccountService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP boundary for WeChat login and current-user profile operations.
 */
@Validated
@RestController
@AllArgsConstructor
@RequestMapping(value = "/api/base/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class BaseController {

    private final UserAccountService userAccountService;

    @PostMapping(value = "/auth/wechat-login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AuthSessionResponse> loginWithWechatCode(@Valid @RequestBody WechatLoginRequest request) {
        return ResponseEntity.ok(userAccountService.loginWithWechatCode(request));
    }

    @GetMapping("/users/me")
    public ResponseEntity<UserProfileResponse> getCurrentUser(
            @RequestHeader("X-User-Id") @NotNull UUID currentUserId
    ) {
        return ResponseEntity.ok(userAccountService.getCurrentUser(currentUserId));
    }

    @PatchMapping(value = "/users/me", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<UserProfileResponse> updateCurrentUser(
            @RequestHeader("X-User-Id") @NotNull UUID currentUserId,
            @Valid @RequestBody UpdateUserProfileRequest request
    ) {
        return ResponseEntity.ok(userAccountService.updateCurrentUser(currentUserId, request));
    }
}
