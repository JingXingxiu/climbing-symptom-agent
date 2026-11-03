package org.javaup.base.service;

import com.baomidou.mybatisplus.spring.service.IService;
import java.util.UUID;
import org.javaup.base.api.dto.AuthSessionResponse;
import org.javaup.base.api.dto.UpdateUserProfileRequest;
import org.javaup.base.api.dto.UserProfileResponse;
import org.javaup.base.api.dto.WechatLoginRequest;
import org.javaup.base.entity.UserAccount;

public interface UserAccountService extends IService<UserAccount> {

    AuthSessionResponse loginWithWechatCode(WechatLoginRequest request);

    UserProfileResponse getCurrentUser(UUID currentUserId);

    UserProfileResponse updateCurrentUser(UUID currentUserId, UpdateUserProfileRequest request);
}
