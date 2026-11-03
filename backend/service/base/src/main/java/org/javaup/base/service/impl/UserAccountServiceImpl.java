package org.javaup.base.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import org.javaup.base.api.dto.AuthSessionResponse;
import org.javaup.base.api.dto.UpdateUserProfileRequest;
import org.javaup.base.api.dto.UserProfileResponse;
import org.javaup.base.api.dto.WechatLoginRequest;
import org.javaup.base.api.problem.ApiException;
import org.javaup.base.auth.AccessTokenService;
import org.javaup.base.client.WechatClient;
import org.javaup.base.entity.UserAccount;
import org.javaup.base.entity.UserLoginIdentity;
import org.javaup.base.entity.UserProfile;
import org.javaup.base.mapper.UserAccountMapper;
import org.javaup.base.service.UserAccountService;
import org.javaup.base.service.UserLoginIdentityService;
import org.javaup.base.service.UserProfileService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@AllArgsConstructor
public class UserAccountServiceImpl extends ServiceImpl<UserAccountMapper, UserAccount>
        implements UserAccountService {

    private final UserLoginIdentityService userLoginIdentityService;
    private final UserProfileService userProfileService;
    private final WechatClient wechatClient;
    private final AccessTokenService accessTokenService;
    private final PlatformTransactionManager transactionManager;

    @Override
    public AuthSessionResponse loginWithWechatCode(WechatLoginRequest request) {
        accessTokenService.requireConfigured();
        // Exchange the one-time code before acquiring database locks.
        WechatClient.WechatSession session = wechatClient.exchange(request.code());
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        UserProfileResponse user;
        try {
            user = transaction.execute(status -> finishWechatLogin(session, request.displayName(), true));
        } catch (DuplicateKeyException conflict) {
            // The failed PostgreSQL transaction has rolled back before this lookup.
            user = transaction.execute(status -> finishWechatLogin(session, null, false));
            if (user == null) {
                throw conflict;
            }
        }
        AccessTokenService.IssuedToken token = accessTokenService.issue(user.userId());
        return new AuthSessionResponse(token.accessToken(), token.expiresInSeconds(), user);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public UserProfileResponse getCurrentUser(UUID currentUserId) {
        UserAccount account = requireActiveAccount(currentUserId, false);
        return UserProfileResponse.fromEntity(account, requireProfile(currentUserId));
    }

    @Override
    @Transactional
    public UserProfileResponse updateCurrentUser(UUID currentUserId, UpdateUserProfileRequest request) {
        UserAccount account = requireActiveAccount(currentUserId, true);
        UserProfile profile = requireProfile(currentUserId);
        if (!request.version().equals(profile.getVersion())) {
            throw new ApiException(HttpStatus.CONFLICT, "USER_PROFILE_VERSION_CONFLICT",
                    "Profile has been modified; reload and retry");
        }
        if (!request.hasDisplayName() && !request.hasAvatarPictureId()) {
            return UserProfileResponse.fromEntity(account, profile);
        }
        int previousVersion = profile.getVersion();
        OffsetDateTime now = OffsetDateTime.now();
        profile.apply(request, now);
        boolean updated = userProfileService.lambdaUpdate()
                .eq(UserProfile::getUserId, currentUserId)
                .eq(UserProfile::getVersion, previousVersion)
                .set(request.hasDisplayName(), UserProfile::getDisplayName, profile.getDisplayName())
                .set(request.hasAvatarPictureId(), UserProfile::getAvatarPictureId, profile.getAvatarPictureId())
                .set(UserProfile::getVersion, profile.getVersion())
                .set(UserProfile::getUpdatedAt, now)
                .update();
        if (!updated) {
            throw new ApiException(HttpStatus.CONFLICT, "USER_PROFILE_VERSION_CONFLICT",
                    "Profile changed while saving; reload and retry");
        }
        return UserProfileResponse.fromEntity(account, profile);
    }

    private UserProfileResponse finishWechatLogin(
            WechatClient.WechatSession session, String initialDisplayName, boolean allowRegistration
    ) {
        UserLoginIdentity identity = userLoginIdentityService.lambdaQuery()
                .eq(UserLoginIdentity::getIdentityType, "WECHAT")
                .eq(UserLoginIdentity::getIssuer, session.appId())
                .eq(UserLoginIdentity::getExternalSubject, session.openId())
                .one();
        OffsetDateTime now = OffsetDateTime.now();
        UserAccount account;
        UserProfile profile;
        if (identity == null) {
            if (!allowRegistration) {
                return null;
            }
            UUID userId = UUID.randomUUID();
            account = UserAccount.create(userId, now);
            profile = UserProfile.create(userId, initialDisplayName, now);
            identity = UserLoginIdentity.create(userId, session.appId(), session.openId(), now);
            if (!save(account) || !userProfileService.save(profile) || !userLoginIdentityService.save(identity)) {
                throw new IllegalStateException("Failed to create the local WeChat account");
            }
        } else {
            account = requireActiveAccount(identity.getUserId(), true);
            profile = requireProfile(identity.getUserId());
            if (!userLoginIdentityService.lambdaUpdate()
                    .eq(UserLoginIdentity::getId, identity.getId())
                    .set(UserLoginIdentity::getLastLoginAt, now)
                    .update()) {
                throw new IllegalStateException("Failed to record the login time");
            }
        }
        // Login hints only initialize new profiles. Pictures use avatarPictureId.
        return UserProfileResponse.fromEntity(account, profile);
    }

    private UserAccount requireActiveAccount(UUID userId, boolean forUpdate) {
        var query = lambdaQuery().eq(UserAccount::getId, userId).isNull(UserAccount::getDeletedAt);
        if (forUpdate) {
            query.last("FOR UPDATE");
        }
        UserAccount account = query.one();
        if (account == null || "DELETED".equals(account.getAccountStatus())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User does not exist");
        }
        if (!"ACTIVE".equals(account.getAccountStatus())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "USER_DISABLED", "User account is disabled");
        }
        return account;
    }

    private UserProfile requireProfile(UUID userId) {
        UserProfile profile = userProfileService.getById(userId);
        if (profile == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_PROFILE_NOT_FOUND", "User profile does not exist");
        }
        return profile;
    }
}
