package org.javaup.base.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.javaup.base.api.dto.AuthSessionResponse;
import org.javaup.base.api.dto.UpdateUserProfileRequest;
import org.javaup.base.api.dto.UserProfileResponse;
import org.javaup.base.api.dto.UserStatus;
import org.javaup.base.api.dto.WechatLoginRequest;
import org.javaup.base.api.problem.ApiException;
import org.javaup.base.auth.AccessTokenService;
import org.javaup.base.client.WechatClient;
import org.javaup.base.entity.UserAccount;
import org.javaup.base.entity.UserLoginIdentity;
import org.javaup.base.entity.UserProfile;
import org.javaup.base.mapper.UserAccountMapper;
import org.javaup.base.mapper.UserLoginIdentityMapper;
import org.javaup.base.mapper.UserProfileMapper;
import org.javaup.base.service.UserLoginIdentityService;
import org.javaup.base.service.UserProfileService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Business rules and transaction boundaries; these tests do not claim real database rollback. */
class UserAccountServiceImplTest {

    private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-10-25T02:00:00Z");
    private static final String APP_ID = "test-mini-program";
    private static final String OPEN_ID = "provider-subject";

    private final UUID userId = UUID.randomUUID();
    private final UUID avatarId = UUID.randomUUID();
    private final AtomicBoolean transactionActive = new AtomicBoolean();
    private final AtomicInteger transactionNumber = new AtomicInteger();
    private final List<TransactionStatus> transactions = new ArrayList<>();

    private UserAccountMapper accountMapper;
    private UserLoginIdentityMapper identityMapper;
    private UserProfileMapper profileMapper;
    private UserLoginIdentityService identityService;
    private UserProfileService profileService;
    private WechatClient wechatClient;
    private AccessTokenService tokenService;
    private PlatformTransactionManager transactionManager;
    private UserAccountServiceImpl service;
    private UserAccount account;
    private UserProfile profile;
    private UserLoginIdentity identity;

    @BeforeAll
    static void initializeLambdaMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        for (Class<?> entity : List.of(UserAccount.class, UserProfile.class, UserLoginIdentity.class)) {
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "base-service-unit-test");
            assistant.setCurrentNamespace(entity.getName());
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @BeforeEach
    void setUp() {
        accountMapper = mock(UserAccountMapper.class);
        identityMapper = mock(UserLoginIdentityMapper.class);
        profileMapper = mock(UserProfileMapper.class);
        identityService = mock(UserLoginIdentityService.class);
        profileService = mock(UserProfileService.class);
        wechatClient = mock(WechatClient.class);
        tokenService = mock(AccessTokenService.class);
        transactionManager = mock(PlatformTransactionManager.class);

        when(identityService.lambdaQuery()).thenAnswer(invocation ->
                new LambdaQueryChainWrapper<>(identityMapper, UserLoginIdentity.class));
        when(identityService.lambdaUpdate()).thenAnswer(invocation ->
                new LambdaUpdateChainWrapper<>(identityMapper));
        when(profileService.lambdaUpdate()).thenAnswer(invocation ->
                new LambdaUpdateChainWrapper<>(profileMapper));
        when(identityService.save(any(UserLoginIdentity.class))).thenReturn(true);
        when(profileService.save(any(UserProfile.class))).thenReturn(true);
        when(accountMapper.insert(any(UserAccount.class))).thenReturn(1);
        when(identityMapper.update(isNull(), any())).thenReturn(1);
        when(profileMapper.update(isNull(), any())).thenReturn(1);

        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> {
            TransactionDefinition definition = invocation.getArgument(0);
            assertEquals(TransactionDefinition.PROPAGATION_REQUIRES_NEW, definition.getPropagationBehavior());
            assertFalse(transactionActive.getAndSet(true), "A failed transaction must end before retrying");
            transactionNumber.incrementAndGet();
            TransactionStatus status = mock(TransactionStatus.class);
            transactions.add(status);
            return status;
        });
        doAnswer(invocation -> {
            assertTrue(transactionActive.getAndSet(false));
            return null;
        }).when(transactionManager).commit(any());
        doAnswer(invocation -> {
            assertTrue(transactionActive.getAndSet(false));
            return null;
        }).when(transactionManager).rollback(any());

        when(wechatClient.exchange("one-use-code")).thenAnswer(invocation -> {
            assertFalse(transactionActive.get(), "Do not hold a database transaction during WeChat HTTP I/O");
            return new WechatClient.WechatSession(APP_ID, OPEN_ID);
        });
        when(tokenService.issue(any(UUID.class))).thenAnswer(invocation -> {
            assertFalse(transactionActive.get(), "Issue a session only after the local transaction commits");
            return new AccessTokenService.IssuedToken("signed-access-token", 7200);
        });

        service = new UserAccountServiceImpl(identityService, profileService, wechatClient, tokenService,
                transactionManager);
        ReflectionTestUtils.setField(service, "baseMapper", accountMapper);
        ReflectionTestUtils.setField(service, "entityClass", UserAccount.class);

        account = account();
        profile = profile();
        identity = identity();
        when(accountMapper.selectOne(any())).thenReturn(account);
        when(profileService.getById(userId)).thenReturn(profile);
    }

    @Test
    void firstLoginCreatesAllThreeRowsAndIssuesATokenAfterCommit() {
        when(identityMapper.selectOne(any())).thenAnswer(invocation -> {
            assertIdentityScope(invocation.getArgument(0));
            assertTrue(transactionActive.get());
            return null;
        });

        AuthSessionResponse response = service.loginWithWechatCode(loginRequest());

        ArgumentCaptor<UserAccount> savedAccount = ArgumentCaptor.forClass(UserAccount.class);
        ArgumentCaptor<UserProfile> savedProfile = ArgumentCaptor.forClass(UserProfile.class);
        ArgumentCaptor<UserLoginIdentity> savedIdentity = ArgumentCaptor.forClass(UserLoginIdentity.class);
        verify(accountMapper).insert(savedAccount.capture());
        verify(profileService).save(savedProfile.capture());
        verify(identityService).save(savedIdentity.capture());
        UUID createdUserId = savedAccount.getValue().getId();
        assertNotNull(createdUserId);
        assertEquals("ACTIVE", savedAccount.getValue().getAccountStatus());
        assertNotNull(savedAccount.getValue().getCreatedAt());
        assertNotNull(savedAccount.getValue().getUpdatedAt());
        assertEquals(createdUserId, savedProfile.getValue().getUserId());
        assertEquals("Initial name", savedProfile.getValue().getDisplayName());
        assertNull(savedProfile.getValue().getAvatarPictureId());
        assertEquals("zh-CN", savedProfile.getValue().getLocale());
        assertEquals(1, savedProfile.getValue().getVersion());
        assertNotNull(savedProfile.getValue().getCreatedAt());
        assertNotNull(savedProfile.getValue().getUpdatedAt());
        assertNotNull(savedIdentity.getValue().getId());
        assertEquals(createdUserId, savedIdentity.getValue().getUserId());
        assertEquals("WECHAT", savedIdentity.getValue().getIdentityType());
        assertEquals(APP_ID, savedIdentity.getValue().getIssuer());
        assertEquals(OPEN_ID, savedIdentity.getValue().getExternalSubject());
        assertNotNull(savedIdentity.getValue().getCreatedAt());
        assertNotNull(savedIdentity.getValue().getLastLoginAt());
        assertEquals(createdUserId, response.user().userId());
        assertEquals("Initial name", response.user().displayName());
        assertEquals("signed-access-token", response.accessToken());
        assertEquals(7200, response.expiresInSeconds());
        InOrder order = inOrder(tokenService, wechatClient, transactionManager, accountMapper,
                profileService, identityService);
        order.verify(tokenService).requireConfigured();
        order.verify(wechatClient).exchange("one-use-code");
        order.verify(transactionManager).getTransaction(any());
        order.verify(accountMapper).insert(any(UserAccount.class));
        order.verify(profileService).save(any(UserProfile.class));
        order.verify(identityService).save(any(UserLoginIdentity.class));
        order.verify(transactionManager).commit(transactions.getFirst());
        order.verify(tokenService).issue(createdUserId);
        verify(transactionManager, never()).rollback(any());
    }

    @Test
    void existingLoginKeepsTheInternalUserIdAndDoesNotOverwriteEditedProfile() {
        when(identityMapper.selectOne(any())).thenAnswer(invocation -> {
            assertIdentityScope(invocation.getArgument(0));
            return identity;
        });

        AuthSessionResponse response = service.loginWithWechatCode(loginRequest());

        assertEquals(userId, response.user().userId());
        assertEquals("Edited name", response.user().displayName());
        assertEquals(avatarId, response.user().avatarPictureId());
        assertEquals(7, response.user().version());
        verify(tokenService).issue(userId);
        verify(accountMapper, never()).insert(any(UserAccount.class));
        verify(profileService, never()).save(any(UserProfile.class));
        verify(identityService, never()).save(any(UserLoginIdentity.class));
        verifyNoInteractions(profileMapper);
        verify(identityMapper).update(isNull(), any());
    }

    @Test
    void missingSigningConfigurationDoesNotConsumeAWechatCodeOrAccessPersistence() {
        ApiException unavailable = new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "AUTH_NOT_CONFIGURED", "Access-token signing is not configured.");
        doThrow(unavailable).when(tokenService).requireConfigured();

        assertSame(unavailable,
                assertThrows(ApiException.class, () -> service.loginWithWechatCode(loginRequest())));

        verifyNoInteractions(wechatClient, accountMapper, identityService, profileService, transactionManager);
        verify(tokenService, never()).issue(any());
    }

    @ParameterizedTest
    @CsvSource({"DISABLED, false, 403", "DELETED, false, 404", "ACTIVE, true, 404"})
    void disabledOrDeletedAccountsCannotLoginReadOrUpdate(String status, boolean deleted, int expectedStatus) {
        account.setAccountStatus(status);
        account.setDeletedAt(deleted ? CREATED_AT.plusDays(1) : null);
        when(identityMapper.selectOne(any())).thenReturn(identity);
        when(accountMapper.selectOne(any())).thenAnswer(invocation -> {
            AbstractWrapper<?, ?, ?> query = invocation.getArgument(0);
            assertThat(query.getSqlSegment().toUpperCase()).contains("DELETED_AT IS NULL");
            return deleted ? null : account;
        });

        assertStatus(expectedStatus, () -> service.loginWithWechatCode(loginRequest()));
        assertStatus(expectedStatus, () -> service.getCurrentUser(userId));
        assertStatus(expectedStatus,
                () -> service.updateCurrentUser(userId, new UpdateUserProfileRequest("Changed", avatarId, 7)));

        verify(tokenService, never()).issue(any());
        verify(profileService, never()).getById(any(UUID.class));
        verifyNoInteractions(profileMapper);
        verify(identityMapper, never()).update(isNull(), any());
    }

    @Test
    void identityUniquenessRaceRetriesOnlyAfterRollbackInANewTransaction() {
        DuplicateKeyException collision = new DuplicateKeyException("uq_user_login_identity_subject");
        AtomicInteger reads = new AtomicInteger();
        when(identityMapper.selectOne(any())).thenAnswer(invocation -> {
            assertIdentityScope(invocation.getArgument(0));
            assertTrue(transactionActive.get());
            int read = reads.incrementAndGet();
            assertEquals(read, transactionNumber.get(), "Never query inside the aborted PostgreSQL transaction");
            return read == 1 ? null : identity;
        });
        when(identityService.save(any(UserLoginIdentity.class))).thenThrow(collision);

        AuthSessionResponse response = service.loginWithWechatCode(loginRequest());

        assertEquals(userId, response.user().userId());
        assertEquals("Edited name", response.user().displayName());
        assertEquals(2, reads.get());
        InOrder order = inOrder(transactionManager, identityMapper, identityService, tokenService);
        order.verify(transactionManager).getTransaction(any());
        order.verify(identityMapper).selectOne(any());
        order.verify(identityService).save(any(UserLoginIdentity.class));
        order.verify(transactionManager).rollback(transactions.get(0));
        order.verify(transactionManager).getTransaction(any());
        order.verify(identityMapper).selectOne(any());
        order.verify(transactionManager).commit(transactions.get(1));
        order.verify(tokenService).issue(userId);
        verify(wechatClient, times(1)).exchange("one-use-code");
        verify(accountMapper, times(1)).insert(any(UserAccount.class));
        verify(profileService, times(1)).save(any(UserProfile.class));
    }

    @Test
    void unrelatedDuplicateKeyIsRethrownWhenNoWinningIdentityExists() {
        DuplicateKeyException collision = new DuplicateKeyException("another_unique_constraint");
        when(identityService.save(any(UserLoginIdentity.class))).thenThrow(collision);

        assertSame(collision,
                assertThrows(DuplicateKeyException.class, () -> service.loginWithWechatCode(loginRequest())));

        assertEquals(2, transactionNumber.get());
        verify(identityMapper, times(2)).selectOne(any());
        verify(tokenService, never()).issue(any());
        assertFalse(transactionActive.get());
    }

    @Test
    void getCurrentUserReturnsPersistedProfileAndScopesTheAccountLookup() {
        when(accountMapper.selectOne(any())).thenAnswer(invocation -> {
            assertAccountScope(invocation.getArgument(0), false);
            return account;
        });

        UserProfileResponse response = service.getCurrentUser(userId);

        assertEquals(userId, response.userId());
        assertEquals(UserStatus.ACTIVE, response.status());
        assertEquals("Edited name", response.displayName());
        assertEquals(avatarId, response.avatarPictureId());
        assertEquals(7, response.version());
        assertEquals(CREATED_AT.toInstant(), response.createdAt());
        assertEquals(CREATED_AT.plusHours(1).toInstant(), response.updatedAt());
        verifyNoInteractions(wechatClient, tokenService, transactionManager, profileMapper);
    }

    @Test
    void missingAccountOrProfileReturnsNotFound() {
        when(accountMapper.selectOne(any())).thenReturn(null);
        assertStatus(404, () -> service.getCurrentUser(userId));
        verify(profileService, never()).getById(any(UUID.class));

        when(accountMapper.selectOne(any())).thenReturn(account);
        when(profileService.getById(userId)).thenReturn(null);
        assertStatus(404, () -> service.getCurrentUser(userId));
    }

    @Test
    void staleVersionStopsBeforeProfileWritesAndLocksTheAccount() throws NoSuchMethodException {
        Transactional transactional = UserAccountServiceImpl.class.getMethod("updateCurrentUser",
                UUID.class, UpdateUserProfileRequest.class).getAnnotation(Transactional.class);
        assertNotNull(transactional, "The account lock and profile CAS must share a transaction");
        when(accountMapper.selectOne(any())).thenAnswer(invocation -> {
            assertAccountScope(invocation.getArgument(0), true);
            return account;
        });

        assertStatus(409,
                () -> service.updateCurrentUser(userId, new UpdateUserProfileRequest("Changed", avatarId, 6)));

        verify(profileService, never()).lambdaUpdate();
        verifyNoInteractions(profileMapper);
    }

    @Test
    void patchPreservesOmittedAvatarAndUsesUserIdAndOldVersionAsCasPredicates() {
        when(profileMapper.update(isNull(), any())).thenAnswer(invocation -> {
            LambdaUpdateWrapper<UserProfile> update = invocation.getArgument(1);
            assertThat(update.getSqlSegment()).contains("user_id", "version");
            assertThat(update.getSqlSet()).contains("display_name", "version", "updated_at");
            assertThat(update.getParamNameValuePairs().values()).contains(userId, 7, 8, "Changed name");
            return 1;
        });

        UserProfileResponse response = service.updateCurrentUser(userId,
                patch("{\"displayName\":\"Changed name\",\"version\":7}"));

        assertEquals("Changed name", response.displayName());
        assertEquals(avatarId, response.avatarPictureId());
        assertEquals(8, response.version());
        assertEquals(CREATED_AT.toInstant(), response.createdAt());
        assertThat(response.updatedAt()).isAfter(CREATED_AT.plusHours(1).toInstant());
    }

    @Test
    void patchExplicitNullClearsBothNullableFields() {
        when(profileMapper.update(isNull(), any())).thenAnswer(invocation -> {
            LambdaUpdateWrapper<UserProfile> update = invocation.getArgument(1);
            assertThat(update.getSqlSet()).contains("display_name", "avatar_picture_id");
            assertThat(update.getParamNameValuePairs().values()).containsNull();
            return 1;
        });

        UserProfileResponse response = service.updateCurrentUser(userId,
                patch("{\"displayName\":null,\"avatarPictureId\":null,\"version\":7}"));

        assertNull(response.displayName());
        assertNull(response.avatarPictureId());
        assertEquals(8, response.version());
    }

    @Test
    void versionOnlyPatchReturnsCurrentProfileWithoutIncrementingOrWriting() {
        UserProfileResponse response = service.updateCurrentUser(userId, patch("{\"version\":7}"));

        assertEquals("Edited name", response.displayName());
        assertEquals(avatarId, response.avatarPictureId());
        assertEquals(7, response.version());
        assertEquals(CREATED_AT.plusHours(1).toInstant(), response.updatedAt());
        verify(profileService, never()).lambdaUpdate();
        verifyNoInteractions(profileMapper);
    }

    @Test
    void zeroRowCasUpdateReturnsConflictInsteadOfReturningUnpersistedProfile() {
        when(profileMapper.update(isNull(), any())).thenReturn(0);

        assertStatus(409,
                () -> service.updateCurrentUser(userId, new UpdateUserProfileRequest("Changed", avatarId, 7)));

        verify(profileMapper).update(isNull(), any());
    }

    private void assertAccountScope(AbstractWrapper<?, ?, ?> query, boolean forUpdate) {
        assertThat(query.getSqlSegment()).contains("id");
        assertThat(query.getParamNameValuePairs().values()).contains(userId);
        assertFalse(query.getSqlSegment().toLowerCase().contains(" or "));
        if (forUpdate) {
            assertThat(query.getSqlSegment().toUpperCase()).contains("FOR UPDATE");
        }
    }

    private static void assertIdentityScope(AbstractWrapper<?, ?, ?> query) {
        assertThat(query.getSqlSegment()).contains("identity_type", "issuer", "external_subject");
        assertEquals(Set.of("WECHAT", APP_ID, OPEN_ID),
                new HashSet<>(query.getParamNameValuePairs().values()));
        assertFalse(query.getSqlSegment().toLowerCase().contains(" or "));
    }

    private static UpdateUserProfileRequest patch(String json) {
        return JsonMapper.builder().build().readValue(json, UpdateUserProfileRequest.class);
    }

    private static WechatLoginRequest loginRequest() {
        return new WechatLoginRequest("one-use-code", "Initial name", "https://example.invalid/avatar.jpg");
    }

    private UserAccount account() {
        UserAccount value = new UserAccount();
        value.setId(userId);
        value.setAccountStatus("ACTIVE");
        value.setCreatedAt(CREATED_AT);
        value.setUpdatedAt(CREATED_AT);
        return value;
    }

    private UserProfile profile() {
        UserProfile value = new UserProfile();
        value.setUserId(userId);
        value.setDisplayName("Edited name");
        value.setAvatarPictureId(avatarId);
        value.setLocale("zh-CN");
        value.setVersion(7);
        value.setCreatedAt(CREATED_AT);
        value.setUpdatedAt(CREATED_AT.plusHours(1));
        return value;
    }

    private UserLoginIdentity identity() {
        UserLoginIdentity value = new UserLoginIdentity();
        value.setId(UUID.randomUUID());
        value.setUserId(userId);
        value.setIdentityType("WECHAT");
        value.setIssuer(APP_ID);
        value.setExternalSubject(OPEN_ID);
        value.setCreatedAt(CREATED_AT);
        value.setLastLoginAt(CREATED_AT);
        return value;
    }

    private static void assertStatus(int status, Runnable action) {
        ApiException exception = assertThrows(ApiException.class, action::run);
        assertEquals(status, exception.status().value());
    }
}
