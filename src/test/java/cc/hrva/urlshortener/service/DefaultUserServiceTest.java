package cc.hrva.urlshortener.service;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.converter.UserToUserDtoConverter;
import cc.hrva.urlshortener.converter.UserUpdateDtoToUserConverter;
import cc.hrva.urlshortener.dto.AdminUserResponse;
import cc.hrva.urlshortener.dto.DataExportDto;
import cc.hrva.urlshortener.dto.DataExportDto.BioButtonExport;
import cc.hrva.urlshortener.dto.DataExportDto.BioPageExport;
import cc.hrva.urlshortener.dto.DataExportDto.ChannelExport;
import cc.hrva.urlshortener.dto.DataExportDto.ConsentExport;
import cc.hrva.urlshortener.dto.DataExportDto.DomainExport;
import cc.hrva.urlshortener.dto.DataExportDto.NotificationExport;
import cc.hrva.urlshortener.dto.DeleteAccountDto;
import cc.hrva.urlshortener.dto.PasswordResetDto;
import cc.hrva.urlshortener.dto.RequestPasswordResetDto;
import cc.hrva.urlshortener.dto.UpdatePasswordDto;
import cc.hrva.urlshortener.dto.UserDto;
import cc.hrva.urlshortener.dto.UserSearchDto;
import cc.hrva.urlshortener.dto.UserUpdateDto;
import cc.hrva.urlshortener.exception.InvalidSortException;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.ReauthenticationRequiredException;
import cc.hrva.urlshortener.exception.UserNotFoundException;
import cc.hrva.urlshortener.listener.SignUpEmailListener;
import cc.hrva.urlshortener.listener.UserRegisteredEvent;
import cc.hrva.urlshortener.mcp.apps.ConnectedAppsService;
import cc.hrva.urlshortener.model.PasskeyCredential;
import cc.hrva.urlshortener.model.ResetToken;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.VerificationToken;
import cc.hrva.urlshortener.model.enums.BioAccent;
import cc.hrva.urlshortener.model.enums.BioAvatarKind;
import cc.hrva.urlshortener.model.enums.BioIcon;
import cc.hrva.urlshortener.model.enums.BioTheme;
import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.model.enums.ConsentSource;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.NotificationType;
import cc.hrva.urlshortener.repository.ApiKeyRepository;
import cc.hrva.urlshortener.repository.PasskeyCredentialRepository;
import cc.hrva.urlshortener.repository.ResetTokenRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.Reauthentication;
import cc.hrva.urlshortener.security.RecentSignIn;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.service.bio.BioAccountData;
import cc.hrva.urlshortener.service.domain.AccountActiveChangedEvent;
import cc.hrva.urlshortener.service.domain.CustomDomainAccountData;
import cc.hrva.urlshortener.service.impl.DefaultLoginAttemptService;
import cc.hrva.urlshortener.service.notification.NotificationAccountData;
import cc.hrva.urlshortener.service.impl.DefaultSendingEmailService;
import cc.hrva.urlshortener.service.impl.DefaultUserService;
import cc.hrva.urlshortener.validator.AuthValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultUserServiceTest {

    private static final String CREDENTIAL_ID = "q1Rz7uCredentialIdOfTheMacBook0aB";
    private static final String PUBLIC_KEY = "pQECAyYgASFYIPublicKeyOfTheMacBookCose";
    private static final String ATTESTATION_OBJECT = "o2NmbXRkbm9uZWdhdHRTdG10oGhAttestationObject";
    private static final String USER_HANDLE = "Xk3vUserHandleOfTheTestAccount43Characters";
    private static final String AAGUID = "fbfc3007-154e-4ecc-8c0b-6e020557d7bd";
    private static final String PASSWORD_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
    private static final String TOTP_SECRET = "JBSWY3DPEHPK3PXP";
    private static final Pattern RECOVERY_CODE_SHAPE = Pattern.compile("\\b[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}\\b");
    private static final Instant NOW = Instant.parse("2026-10-25T14:00:00Z");
    private static final String SIGNED_IN_TOKEN = "header.payload.signature";
    private static final String SIGN_IN_AGAIN_TO_DELETE = "For your security, sign in again to delete your account.";
    private static final String WRONG_PASSWORD = "Current password is incorrect";
    private static final String TOO_MANY_WRONG_PASSWORDS = "Too many wrong passwords. Try again in 24 hours.";

    private UserService userService;

    @Mock
    private AppProperties appProperties;

    @Mock
    private AuthValidator authValidator;

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ApiKeyRepository apiKeyRepository;

    @Mock
    private cc.hrva.urlshortener.repository.EmailLogRepository emailLogRepository;

    @Mock
    private ResetTokenService resetTokenService;

    @Mock
    private ResetTokenRepository resetTokenRepository;

    @Mock
    private cc.hrva.urlshortener.repository.TwoFactorRecoveryCodeRepository twoFactorRecoveryCodeRepository;

    @Mock
    private PasskeyCredentialRepository passkeyCredentialRepository;

    @Mock
    private BioAccountData bioAccountData;

    @Mock
    private NotificationAccountData notificationAccountData;

    @Mock
    private CustomDomainAccountData customDomainAccountData;

    @Mock
    private UserToUserDtoConverter userToUserDtoConverter;

    @Mock
    private DefaultSendingEmailService sendingEmailService;

    @Mock
    private cc.hrva.urlshortener.service.VerificationTokenService verificationTokenService;

    @Mock
    private cc.hrva.urlshortener.repository.VerificationTokenRepository verificationTokenRepository;

    @Mock
    private UserUpdateDtoToUserConverter userUpdateDtoToUserConverter;

    @Mock
    private ConnectedAppsService connectedAppsService;

    @Mock
    private ScammerSignalService scammerSignalService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private TokenProvider tokenProvider;

    private DefaultLoginAttemptService loginAttemptService;

    @BeforeEach
    void setUp() {
        final var loginAttemptProperties = new AppProperties();
        loginAttemptProperties.setMaxLoginAttempts(20L);
        loginAttemptService = new DefaultLoginAttemptService(loginAttemptProperties);
        this.userService = userServicePublishingTo(eventPublisher);
        lenient().when(userRepository.findByEmail(null)).thenReturn(Optional.empty());
    }

    private DefaultUserService userServicePublishingTo(final ApplicationEventPublisher publisher) {
        final var reauthentication = new Reauthentication(new RecentSignIn(tokenProvider, Clock.fixed(NOW, ZoneOffset.UTC)), authValidator,
                loginAttemptService);
        return new DefaultUserService(appProperties, reauthentication, urlRepository, userRepository,
                passwordEncoder, apiKeyRepository, emailLogRepository, resetTokenService,
                resetTokenRepository, twoFactorRecoveryCodeRepository, passkeyCredentialRepository, bioAccountData, notificationAccountData, customDomainAccountData, userToUserDtoConverter, sendingEmailService,
                verificationTokenService, verificationTokenRepository, userUpdateDtoToUserConverter, connectedAppsService, scammerSignalService, publisher);
    }

    @Test
    void deletingAnAccountClearsItsLinksFromTheRedirectCache() throws NoSuchMethodException {
        for (final var method : java.util.List.of(
                DefaultUserService.class.getMethod("deleteUserById", Long.class),
                DefaultUserService.class.getMethod("deleteOwnAccount", cc.hrva.urlshortener.dto.DeleteAccountDto.class))) {
            final var evict = method.getAnnotation(org.springframework.cache.annotation.CacheEvict.class);

            assertThat(evict).as(method.getName()).isNotNull();
            assertThat(evict.value()).containsExactly("urls");
            assertThat(evict.allEntries()).isTrue();
        }
    }

    @Test
    void shouldRegisterUser() {
        final var user = User.builder().email("test@example.com").build();
        final var token = VerificationToken.builder().token("verify-me").build();
        when(userRepository.save(user)).thenReturn(user);
        when(verificationTokenService.createTokenForUser(user)).thenReturn(token);

        final var result = userService.register(user);

        assertThat(result.getEmail()).isEqualTo("test@example.com");
        verify(eventPublisher).publishEvent(new UserRegisteredEvent(user, token));
        verifyNoInteractions(sendingEmailService);
    }

    @Test
    void aRegistrationThatRollsBackSendsNoEmail() {
        final var user = User.builder().id(31L).email("a".repeat(65) + "@example.com").build();
        when(userRepository.save(user)).thenReturn(user);
        when(verificationTokenService.createTokenForUser(user)).thenReturn(VerificationToken.builder().token("verify-me").build());

        try (final var context = signUpEmailsContext()) {
            final var registering = userServicePublishingTo(context);
            new TransactionTemplate(new NoResourceTransactionManager()).executeWithoutResult(status -> {
                registering.register(user);
                status.setRollbackOnly();
            });
        }

        verifyNoInteractions(sendingEmailService);
    }

    @Test
    void aRegistrationSendsTheVerificationAndAdminEmailsOnlyOnceItCommits() {
        final var user = User.builder().id(31L).email("ana@example.com").build();
        final var token = VerificationToken.builder().token("verify-me").build();
        when(userRepository.save(user)).thenReturn(user);
        when(verificationTokenService.createTokenForUser(user)).thenReturn(token);

        try (final var context = signUpEmailsContext()) {
            final var registering = userServicePublishingTo(context);
            new TransactionTemplate(new NoResourceTransactionManager()).executeWithoutResult(status -> {
                registering.register(user);
                verifyNoInteractions(sendingEmailService);
            });
        }

        verify(sendingEmailService).sendVerificationEmail(user, token);
        verify(sendingEmailService).sendNewUserNotificationToAdmin(user);
        verifyNoMoreInteractions(sendingEmailService);
    }

    @Test
    void aRegistrationAlertsTheAdminWithTheAccountIdButNotTheEmail() {
        final var user = User.builder().id(31L).email("test@example.com").authProvider("local").build();
        when(userRepository.save(user)).thenReturn(user);

        userService.register(user);

        verify(eventPublisher).publishEvent(new AdminAlertEvent.UserSignedUp(31L, "local"));
    }

    @Test
    void shouldFetchCurrentUser() {
        final var user = User.builder().id(1L).email("test@example.com").build();
        final var userDto = UserDto.builder().id(1L).email("test@example.com").build();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("test@example.com", null)
        );
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(userToUserDtoConverter.convert(user)).thenReturn(userDto);

        final var result = userService.fetchCurrentUser();

        assertThat(result.getEmail()).isEqualTo("test@example.com");
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldFailFetchCurrentUserWhenNotAuthenticated() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(null, null)
        );
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.fetchCurrentUser())
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage("Invalid credentials");
        SecurityContextHolder.clearContext();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void shouldFetchUserFromEmail(final CapturedOutput output) {
        final var user = User.builder().id(1L).email("test@example.com").active(true).build();
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        final var result = userService.fetchUserFromEmail("test@example.com");

        assertThat(result).isEqualTo(user);
        assertThat(output).contains("User login email=t***@example.com").doesNotContain("test@example.com");
    }

    @Test
    void shouldFailFetchUserFromEmailWhenInactive() {
        final var user = User.builder().id(1L).email("test@example.com").active(false).build();
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.fetchUserFromEmail("test@example.com"))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessage("User is inactive, please contact administrator");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void shouldFetchActiveUserByEmailWithoutLoggingTheEmail(final CapturedOutput output) {
        final var user = User.builder().id(1L).email("quiet@example.com").active(true).build();
        when(userRepository.findByEmail("quiet@example.com")).thenReturn(Optional.of(user));

        final var result = userService.fetchActiveUserByEmail("quiet@example.com");

        assertThat(result).isEqualTo(user);
        assertThat(output).doesNotContain("quiet@example.com");
    }

    @Test
    void shouldFailFetchActiveUserByEmailWhenInactive() {
        final var user = User.builder().id(1L).email("test@example.com").active(false).build();
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.fetchActiveUserByEmail("test@example.com"))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessage("User is inactive, please contact administrator");
    }

    @Test
    void shouldFailFetchActiveUserByEmailWhenUnknown() {
        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.fetchActiveUserByEmail("missing@example.com"))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessage("User not found");
    }

    @Test
    void shouldFetchAllUsers() {
        final var pageable = PageRequest.of(0, 20);
        final var users = new PageImpl<>(Collections.singletonList(User.builder().id(1L).build()));
        when(userRepository.findAll(newestFirst(pageable))).thenReturn(users);

        assertThat(userService.fetchAllUsers(pageable, new UserSearchDto()).getContent()).extracting(AdminUserResponse::id).containsExactly(1L);
    }

    @Test
    void theUserListWithoutASortComesNewestFirstWithTheIdBreakingTies() {
        when(userRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());

        userService.fetchAllUsers(PageRequest.of(2, 20), new UserSearchDto());

        verify(userRepository).findAll(PageRequest.of(2, 20, Sort.by(
                Sort.Order.desc("createDate"), Sort.Order.desc("id"))));
    }

    @Test
    void theUserListRefusesToSortByAFieldOffTheList() {
        assertThatThrownBy(() -> userService.fetchAllUsers(PageRequest.of(0, 20, Sort.by("password")), new UserSearchDto()))
                .isInstanceOf(InvalidSortException.class)
                .hasMessage("Sort must be email, createDate, lastLogin or active");
        verifyNoInteractions(userRepository);
    }

    @Test
    void theUserListMarksEachPossibleScammerFromOneLookup() {
        final var pageable = PageRequest.of(0, 20);
        final var scammer = User.builder().id(27L).email("rhea@gmail.com").build();
        final var honest = User.builder().id(28L).email("ana@example.com").build();
        when(userRepository.findAll(newestFirst(pageable))).thenReturn(new PageImpl<>(List.of(scammer, honest)));
        when(scammerSignalService.possibleScammerIds()).thenReturn(Set.of(27L, 41L));

        final var users = userService.fetchAllUsers(pageable, new UserSearchDto());

        assertThat(users.getContent()).extracting(AdminUserResponse::id, AdminUserResponse::possibleScammer)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(27L, true), org.assertj.core.groups.Tuple.tuple(28L, false));
        verify(scammerSignalService).possibleScammerIds();
    }

    @Test
    void thePossibleScammerFilterIsAppliedInTheQuerySoTheTotalsStayRight() {
        final var pageable = PageRequest.of(0, 20);
        final var scammer = User.builder().id(27L).email("rhea@gmail.com").build();
        final var search = new UserSearchDto();
        search.setPossibleScammer(true);
        when(scammerSignalService.possibleScammerIds()).thenReturn(Set.of(27L));
        when(userRepository.findAll(any(Specification.class), eq(newestFirst(pageable)))).thenReturn(new PageImpl<>(List.of(scammer), pageable, 1));

        final var users = userService.fetchAllUsers(pageable, search);

        assertThat(users.getTotalElements()).isEqualTo(1);
        assertThat(users.getContent()).singleElement().extracting(AdminUserResponse::possibleScammer).isEqualTo(true);
        verify(userRepository, never()).findAll(newestFirst(pageable));
    }

    @Test
    void thePossibleScammerFilterWithNoPossibleScammersIsAnEmptyPageWithoutAQuery() {
        final var pageable = PageRequest.of(0, 20);
        final var search = new UserSearchDto();
        search.setPossibleScammer(true);

        final var users = userService.fetchAllUsers(pageable, search);

        assertThat(users.getContent()).isEmpty();
        assertThat(users.getTotalElements()).isZero();
        verifyNoInteractions(userRepository);
    }

    @Test
    void shouldFetchAllUsersWithSearchFilter() {
        final var pageable = PageRequest.of(0, 20);
        final var user = User.builder().id(1L).email("test@example.com").build();
        final var users = new PageImpl<>(Collections.singletonList(user));
        final var search = new UserSearchDto();
        search.setSearch("example");

        when(userRepository.findAll(any(Specification.class), eq(newestFirst(pageable)))).thenReturn(users);

        assertThat(userService.fetchAllUsers(pageable, search).getContent()).extracting(AdminUserResponse::email).containsExactly("test@example.com");
    }

    @Test
    void shouldFetchAllUsersWithActiveFilter() {
        final var pageable = PageRequest.of(0, 20);
        final var user = User.builder().id(1L).email("test@example.com").active(true).build();
        final var users = new PageImpl<>(Collections.singletonList(user));
        final var search = new UserSearchDto();
        search.setActive(true);

        when(userRepository.findAll(any(Specification.class), eq(newestFirst(pageable)))).thenReturn(users);

        assertThat(userService.fetchAllUsers(pageable, search).getContent()).extracting(AdminUserResponse::active).containsExactly(true);
    }

    @Test
    void shouldDeleteUserByIdWithOwnedData() {
        final var user = User.builder().id(1L).email("test@example.com").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        userService.deleteUserById(1L);

        verify(resetTokenRepository).deleteByUser(user);
        verify(urlRepository).deleteByOwner(user);
        verify(userRepository).delete(user);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void deletingAnAccountDeletesItsPasskeysBeforeTheAccount(final CapturedOutput output) {
        final var user = User.builder().id(1L).email("test@example.com").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        userService.deleteUserById(1L);

        final var order = inOrder(passkeyCredentialRepository, userRepository);
        order.verify(passkeyCredentialRepository).deleteByUser(user);
        order.verify(userRepository).delete(user);
        assertThat(output).contains("Deleted user id=1 and all owned data");
    }

    @Test
    void deletingAnAccountDeletesItsBioPageFirst() {
        final var user = User.builder().id(1L).email("test@example.com").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        userService.deleteUserById(1L);

        final var order = inOrder(bioAccountData, urlRepository, userRepository);
        order.verify(bioAccountData).deleteFor(user);
        order.verify(urlRepository).deleteByOwner(user);
        order.verify(userRepository).delete(user);
    }

    @Test
    void deletingAnAccountDeletesItsNotificationDataBeforeItsLinksAndTheAccount() {
        final var user = User.builder().id(1L).email("test@example.com").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        userService.deleteUserById(1L);

        final var order = inOrder(notificationAccountData, urlRepository, userRepository);
        order.verify(notificationAccountData).deleteFor(user);
        order.verify(urlRepository).deleteByOwner(user);
        order.verify(userRepository).delete(user);
    }

    @Test
    void domainsAreReleasedBeforeLinksAndPurgedAfter() {
        final var user = User.builder().id(1L).email("test@example.com").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(customDomainAccountData.releaseFor(user)).thenReturn(List.of(7L, 9L));

        userService.deleteUserById(1L);

        final var order = inOrder(customDomainAccountData, urlRepository, userRepository);
        order.verify(customDomainAccountData).releaseFor(user);
        order.verify(urlRepository).deleteByOwner(user);
        order.verify(customDomainAccountData).purgeFor(List.of(7L, 9L));
        order.verify(userRepository).delete(user);
    }

    @Test
    void deletingTheOwnAccountReleasesItsDomainsTheSameWay() {
        final var user = User.builder().id(1L).email("test@example.com").authProvider("google").build();
        signedInMinutesAgo("test@example.com", 5);
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(customDomainAccountData.releaseFor(user)).thenReturn(List.of(7L));

        userService.deleteOwnAccount(new DeleteAccountDto());

        final var order = inOrder(customDomainAccountData, urlRepository, userRepository);
        order.verify(customDomainAccountData).releaseFor(user);
        order.verify(urlRepository).deleteByOwner(user);
        order.verify(customDomainAccountData).purgeFor(List.of(7L));
        order.verify(userRepository).delete(user);
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldFailDeleteUserByIdWhenNotFound() {
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.deleteUserById(1L))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void shouldUpdateUser() {
        final var updateDto = UserUpdateDto.builder().id(1L).build();
        final var existingUser = User.builder().id(1L).authProvider("local").build();
        final var user = User.builder().id(1L).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userUpdateDtoToUserConverter.convert(updateDto)).thenReturn(user);
        when(userRepository.save(existingUser)).thenReturn(existingUser);

        final var result = userService.updateUser(updateDto);

        assertThat(result.id()).isEqualTo(1L);
    }

    @Test
    void anEditIsAppliedToTheUserLoadedInTheWriteTransactionAndThatUserIsSaved() {
        final var updateDto = UserUpdateDto.builder().id(1L).email("new@example.com").apiKeySlots(9L).build();
        final var existingUser = User.builder().id(1L).email("old@example.com").password("secret-hash").authProvider("local")
                .apiKeySlots(3L).active(true).build();
        final var edited = User.builder().id(1L).email("new@example.com").apiKeySlots(9L).active(true).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userUpdateDtoToUserConverter.convert(updateDto)).thenReturn(edited);
        when(userRepository.save(existingUser)).thenReturn(existingUser);

        final var result = userService.updateUser(updateDto);

        verify(userRepository).save(existingUser);
        verify(userRepository, never()).save(edited);
        assertThat(existingUser.getPassword()).isEqualTo("secret-hash");
        assertThat(existingUser.getEmail()).isEqualTo("new@example.com");
        assertThat(existingUser.getApiKeySlots()).isEqualTo(9L);
        assertThat(result.email()).isEqualTo("new@example.com");
    }

    @Test
    void shouldUpdatePassword() {
        final var user = User.builder().id(1L).password("oldEncoded").build();
        final var updateDto = UpdatePasswordDto.builder().oldPassword("oldPassword").newPassword("newPassword").build();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("test@example.com", null)
        );
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newPassword")).thenReturn("newEncoded");
        when(userRepository.save(user)).thenReturn(user);

        final var result = userService.updatePassword(updateDto);

        assertThat(result.getPassword()).isEqualTo("newEncoded");
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldSendPasswordResetLink() {
        final var requestDto = RequestPasswordResetDto.builder().email("test@example.com").build();
        final var user = User.builder().id(1L).email("test@example.com").build();
        final var token = ResetToken.builder().id(1L).build();

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(resetTokenService.createTokenForUser(user)).thenReturn(token);

        userService.sendPasswordResetLinkToUser(requestDto);

        verify(resetTokenService).deactivateActiveResetTokenIfExists(user);
        verify(sendingEmailService).sendEmailForgotPassword(user, token);
    }

    @Test
    void shouldNotSendPasswordResetLinkWhenUserNotFound() {
        final var requestDto = RequestPasswordResetDto.builder().email("test@example.com").build();

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.empty());

        userService.sendPasswordResetLinkToUser(requestDto);

        verify(resetTokenService, org.mockito.Mockito.never()).deactivateActiveResetTokenIfExists(any());
    }

    @Test
    void shouldResetPassword() {
        final var resetDto = PasswordResetDto.builder().email("test@example.com").token("token").password("newPassword").build();
        final var user = User.builder().id(1L).email("test@example.com").build();
        final var token = ResetToken.builder().id(1L).active(true).build();

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(resetTokenService.getResetTokenFromUserAndToken(user, "token")).thenReturn(token);
        when(passwordEncoder.encode("newPassword")).thenReturn("newEncoded");
        when(userRepository.save(user)).thenReturn(user);
        org.mockito.Mockito.doAnswer(inv -> {
            ResetToken t = inv.getArgument(0);
            t.setActive(false);
            return null;
        }).when(resetTokenService).deactivateAndSaveToken(token);

        final var result = userService.resetPassword(resetDto);

        assertThat(result.getPassword()).isEqualTo("newEncoded");
        assertThat(token.isActive()).isFalse();
    }

    @Test
    void aPasswordResetLeavesTwoStepOn() {
        final var resetDto = PasswordResetDto.builder().email("test@example.com").token("token").password("newPassword").build();
        final var user = User.builder().id(1L).email("test@example.com").twoStepEnabled(true).twoFactorEnabled(true)
                .twoFactorSecret("SECRET").build();
        final var token = ResetToken.builder().id(1L).active(true).build();
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(resetTokenService.getResetTokenFromUserAndToken(user, "token")).thenReturn(token);
        when(passwordEncoder.encode("newPassword")).thenReturn("newEncoded");
        when(userRepository.save(user)).thenReturn(user);

        final var result = userService.resetPassword(resetDto);

        assertThat(result.getPassword()).isEqualTo("newEncoded");
        assertThat(result.getTwoStepEnabled()).isTrue();
        assertThat(result.getTwoFactorEnabled()).isTrue();
        assertThat(result.getTwoFactorSecret()).isEqualTo("SECRET");
        verifyNoInteractions(twoFactorRecoveryCodeRepository);
    }

    @Test
    void aPasswordResetClearsTheWrongPasswordCounter() {
        final var resetDto = PasswordResetDto.builder().email("test@example.com").token("token").password("newPassword").build();
        final var user = User.builder().id(1L).email("test@example.com").authProvider("local").password("enc").build();
        final var token = ResetToken.builder().id(1L).active(true).build();
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(resetTokenService.getResetTokenFromUserAndToken(user, "token")).thenReturn(token);
        when(passwordEncoder.encode("newPassword")).thenReturn("newEncoded");
        when(userRepository.save(user)).thenReturn(user);
        IntStream.rangeClosed(1, 20).forEach(attempt -> loginAttemptService.loginFailed("reauth:1"));
        assertThat(loginAttemptService.isBlocked("reauth:1")).isTrue();

        userService.resetPassword(resetDto);

        assertThat(loginAttemptService.getLoginAttempts()).doesNotContainKey("reauth:1");
        assertThat(loginAttemptService.isBlocked("reauth:1")).isFalse();
    }

    @Test
    void aRefusedPasswordResetKeepsTheWrongPasswordCounter() {
        final var resetDto = PasswordResetDto.builder().email("test@example.com").token("token").password("newPassword").build();
        final var user = User.builder().id(1L).email("test@example.com").build();
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(resetTokenService.getResetTokenFromUserAndToken(user, "token")).thenReturn(ResetToken.builder().id(1L).active(false).build());
        IntStream.rangeClosed(1, 20).forEach(attempt -> loginAttemptService.loginFailed("reauth:1"));

        assertThatThrownBy(() -> userService.resetPassword(resetDto)).hasMessage("Token expired");

        assertThat(loginAttemptService.isBlocked("reauth:1")).isTrue();
    }

    @Test
    void shouldFailResetPasswordWhenTokenInactive() {
        final var resetDto = PasswordResetDto.builder().email("test@example.com").token("token").password("newPassword").build();
        final var user = User.builder().id(1L).email("test@example.com").build();
        final var token = ResetToken.builder().id(1L).active(false).build();

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(resetTokenService.getResetTokenFromUserAndToken(user, "token")).thenReturn(token);

        assertThatThrownBy(() -> userService.resetPassword(resetDto))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage("Token expired");
    }

    @Test
    void shouldDeactivateUnusedUserAccounts() {
        final var user = User.builder().id(1L).active(true).build();
        when(appProperties.getDeactivateUserAccountAfterDays()).thenReturn(30L);
        when(userRepository.findByLastLoginIsLessThanEqualAndActiveTrue(any(java.time.LocalDateTime.class)))
                .thenReturn(Collections.singletonList(user));
        when(userRepository.saveAll(any())).thenReturn(Collections.singletonList(user));

        userService.deactivateUnusedUserAccounts();

        assertThat(user.getActive()).isFalse();
        verify(sendingEmailService).sendEmailAccountDeactivated(user);
    }

    @Test
    void deactivatingUnusedAccountsReloadsTheDomainRoutesOnceAfterSavingThem() {
        final var first = User.builder().id(1L).email("idle-one@example.com").active(true).build();
        final var second = User.builder().id(2L).email("idle-two@example.com").active(true).build();
        when(appProperties.getDeactivateUserAccountAfterDays()).thenReturn(30L);
        when(userRepository.findByLastLoginIsLessThanEqualAndActiveTrue(any(java.time.LocalDateTime.class))).thenReturn(List.of(first, second));

        userService.deactivateUnusedUserAccounts();

        final var inOrder = inOrder(userRepository, eventPublisher);
        inOrder.verify(userRepository).saveAll(List.of(first, second));
        inOrder.verify(eventPublisher).publishEvent(new AccountActiveChangedEvent(List.of(1L, 2L)));
        verify(eventPublisher, times(1)).publishEvent(any(AccountActiveChangedEvent.class));
    }

    @Test
    void deactivatingNoUnusedAccountsReloadsNothing() {
        when(appProperties.getDeactivateUserAccountAfterDays()).thenReturn(30L);
        when(userRepository.findByLastLoginIsLessThanEqualAndActiveTrue(any(java.time.LocalDateTime.class))).thenReturn(List.of());

        userService.deactivateUnusedUserAccounts();

        verify(eventPublisher, never()).publishEvent(any(AccountActiveChangedEvent.class));
    }

    @Test
    void shouldRevokeApiKeysWhenDeactivatingUnusedAccount() {
        final var user = User.builder().id(1L).active(true).build();
        when(appProperties.getDeactivateUserAccountAfterDays()).thenReturn(30L);
        when(userRepository.findByLastLoginIsLessThanEqualAndActiveTrue(any(java.time.LocalDateTime.class)))
                .thenReturn(Collections.singletonList(user));
        when(userRepository.saveAll(any())).thenReturn(Collections.singletonList(user));

        userService.deactivateUnusedUserAccounts();

        verify(apiKeyRepository).deactivateActiveByOwnerId(1L);
        verify(apiKeyRepository, never()).saveAll(any());
    }

    @Test
    void deactivatingAUserTurnsOnlyItsActiveColumnOffRevokesItsKeysDisconnectsItsAppsAndTellsIt() {
        final var user = User.builder().id(1L).email("scammer@example.com").active(true).build();
        when(userRepository.deactivateById(1L)).thenReturn(1);

        userService.deactivate(user);

        verify(userRepository).deactivateById(1L);
        verify(userRepository, never()).save(any());
        verify(apiKeyRepository).deactivateActiveByOwnerId(1L);
        verify(apiKeyRepository, never()).saveAll(any());
        verify(connectedAppsService).disconnectAll("scammer@example.com");
        verify(sendingEmailService).sendEmailAccountDeactivated(user);
    }

    @Test
    void deactivatingOrReactivatingAUserLeavesItsDomainsAlone() {
        final var user = User.builder().id(1L).email("scammer@example.com").active(true).build();
        when(userRepository.deactivateById(1L)).thenReturn(1);
        when(userRepository.reactivateById(1L)).thenReturn(1);

        userService.deactivate(user);
        userService.reactivate(1L);

        verifyNoInteractions(customDomainAccountData);
    }

    @Test
    void deactivatingOrReactivatingAUserReloadsTheDomainRoutes() {
        final var user = User.builder().id(1L).email("scammer@example.com").active(true).build();
        when(userRepository.deactivateById(1L)).thenReturn(1);
        when(userRepository.reactivateById(1L)).thenReturn(1);
        when(userRepository.reactivateById(2L)).thenReturn(0);

        userService.deactivate(user);
        userService.reactivate(1L);
        userService.reactivate(2L);

        verify(eventPublisher, times(2)).publishEvent(new AccountActiveChangedEvent(List.of(1L)));
        verify(eventPublisher, never()).publishEvent(new AccountActiveChangedEvent(List.of(2L)));
    }

    @Test
    void anAdminUpdateThatTurnsAnAccountOffOrOnReloadsTheDomainRoutes() {
        final var existingUser = User.builder().id(1L).email("test@example.com").authProvider("local").active(true).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        final var off = UserUpdateDto.builder().id(1L).active(false).build();
        final var on = UserUpdateDto.builder().id(1L).active(true).build();
        when(userUpdateDtoToUserConverter.convert(off)).thenReturn(User.builder().id(1L).email("test@example.com").active(false).build());
        when(userUpdateDtoToUserConverter.convert(on)).thenReturn(User.builder().id(1L).email("test@example.com").active(true).build());
        when(userRepository.deactivateById(1L)).thenReturn(1);
        when(userRepository.reactivateById(1L)).thenReturn(1, 0);

        userService.updateUser(off);
        userService.updateUser(on);
        userService.updateUser(on);

        verify(eventPublisher, times(2)).publishEvent(new AccountActiveChangedEvent(List.of(1L)));
    }

    @Test
    void deactivatingAUserThatIsAlreadyInactiveLeavesItsKeysAppsAndInboxAlone() {
        final var user = User.builder().id(1L).email("scammer@example.com").active(true).build();
        when(userRepository.deactivateById(1L)).thenReturn(0);

        userService.deactivate(user);

        verifyNoInteractions(apiKeyRepository, connectedAppsService, sendingEmailService);
    }

    @Test
    void reactivatingAUserTurnsOnlyItsActiveColumnOnAndSaysWhetherItWasOff() {
        when(userRepository.reactivateById(1L)).thenReturn(1);
        when(userRepository.reactivateById(2L)).thenReturn(0);

        assertThat(userService.reactivate(1L)).isTrue();
        assertThat(userService.reactivate(2L)).isFalse();
        verify(userRepository, never()).save(any());
    }

    @Test
    void shouldExportMyData() {
        final var user = User.builder().id(1L).email("test@example.com").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("test@example.com", null));
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(urlRepository.findByOwner(user)).thenReturn(Collections.emptyList());
        when(apiKeyRepository.findByOwner(user)).thenReturn(Collections.emptyList());
        when(emailLogRepository.findByRecipientContainingIgnoreCase("test@example.com")).thenReturn(Collections.emptyList());
        when(userToUserDtoConverter.convert(user)).thenReturn(UserDto.builder().id(1L).email("test@example.com").build());

        final var export = userService.exportMyData();

        assertThat(export.profile().getEmail()).isEqualTo("test@example.com");
        assertThat(export.urls()).isEmpty();
        assertThat(export.apiKeys()).isEmpty();
        assertThat(export.emails()).isEmpty();
        assertThat(export.passkeys()).isEmpty();
        assertThat(export.recoveryCodesRemaining()).isZero();
        assertThat(export.bioPage()).isNull();
        assertThat(export.exportedAt()).isNotNull();
        SecurityContextHolder.clearContext();
    }

    @Test
    void exportCarriesTheBioPageButNoViewCounts() {
        final var user = User.builder().id(1L).email("test@example.com").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("test@example.com", null));
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(userToUserDtoConverter.convert(user)).thenReturn(UserDto.builder().id(1L).email("test@example.com").build());
        when(bioAccountData.exportFor(user)).thenReturn(Optional.of(new BioPageExport("ana", "Ana Horvat", "Photos from Zagreb",
                BioAvatarKind.INITIALS, null, BioAccent.TEAL, BioTheme.AUTO, false, true, "Impersonation", null,
                List.of(new BioButtonExport("Shop", BioIcon.INSTAGRAM, "ana-shop", true), new BioButtonExport("Blog", null, "ana-blog", false)),
                LocalDateTime.of(2026, 10, 20, 8, 0), LocalDateTime.of(2026, 10, 25, 18, 30))));

        final var mapper = JsonMapper.builder().build();
        final var json = mapper.writeValueAsString(userService.exportMyData());
        final var export = mapper.readTree(json);

        assertThat(export.at("/bioPage/handle").asString()).isEqualTo("ana");
        assertThat(export.get("bioPage").toString()).isEqualTo("""
                {"handle":"ana","displayName":"Ana Horvat","bio":"Photos from Zagreb","avatarKind":"INITIALS","avatarEmoji":null,\
                "accent":"TEAL","theme":"AUTO","indexable":false,"hidden":true,"hiddenReason":"Impersonation",\
                "handleRevokedReason":null,"buttons":[{"title":"Shop","icon":"INSTAGRAM","shortUrl":"ana-shop","visible":true},\
                {"title":"Blog","icon":null,"shortUrl":"ana-blog","visible":false}],\
                "createdAt":"2026-10-20T08:00:00","updatedAt":"2026-10-25T18:30:00"}""");
        assertThat(json).doesNotContain("\"views\"", "\"botViews\"", "\"hiddenBy\"", "\"reasons\"", "\"urlId\"");
        SecurityContextHolder.clearContext();
    }

    @Test
    void theDomainsComeLastInTheExport() {
        assertThat(Arrays.stream(DataExportDto.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("exportedAt", "profile", "urls", "apiKeys", "emails", "passkeys", "recoveryCodesRemaining", "bioPage",
                        "notifications", "domains");
        assertThat(Arrays.stream(DomainExport.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("hostname", "status", "reason", "reviewNote", "createdAt", "reviewedAt", "activatedAt", "disabledAt", "deletedAt",
                        "disabledReason", "disabledNote");
    }

    @Test
    void exportCarriesTheDomains() {
        final var user = User.builder().id(1L).email("test@example.com").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("test@example.com", null));
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(userToUserDtoConverter.convert(user)).thenReturn(UserDto.builder().id(1L).email("test@example.com").build());
        final var domain = new DomainExport("go.example.com", CustomDomainStatus.ACTIVE, "Menu links for my cafe", null,
                LocalDateTime.of(2026, 10, 19, 9, 0), LocalDateTime.of(2026, 10, 20, 9, 0), LocalDateTime.of(2026, 10, 21, 9, 0), null, null, null, null);
        when(customDomainAccountData.exportFor(user)).thenReturn(List.of(domain));

        assertThat(userService.exportMyData().domains()).containsExactly(domain);
        SecurityContextHolder.clearContext();
    }

    @Test
    void exportCarriesTheNotificationConsentsAndChannels() {
        final var user = User.builder().id(1L).email("test@example.com").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("test@example.com", null));
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(userToUserDtoConverter.convert(user)).thenReturn(UserDto.builder().id(1L).email("test@example.com").build());
        when(notificationAccountData.exportFor(user)).thenReturn(new NotificationExport(
                List.of(new ConsentExport(NotificationType.LINK_DEAD, "Discord: Team alerts", LocalDateTime.of(2026, 10, 27, 8, 15, 2),
                        ConsentSource.SETTINGS, null, null)),
                List.of(new ChannelExport("Team alerts", ChannelType.DISCORD, true, List.of(NotificationType.LINK_DEAD),
                        "https://discord.com/••••abcd", null, LocalDateTime.of(2026, 10, 27, 8, 15, 2), null, null, null, 0))));

        final var mapper = JsonMapper.builder().build();
        final var export = mapper.readTree(mapper.writeValueAsString(userService.exportMyData()));

        assertThat(export.get("notifications").toString()).isEqualTo("""
                {"consentHistory":[{"type":"LINK_DEAD","channelLabel":"Discord: Team alerts","grantedAt":"2026-10-27T08:15:02",\
                "source":"SETTINGS","revokedAt":null,"revokedSource":null}],\
                "channels":[{"name":"Team alerts","type":"DISCORD","enabled":true,"events":["LINK_DEAD"],\
                "maskedTarget":"https://discord.com/••••abcd","chatId":null,"createdAt":"2026-10-27T08:15:02","lastDeliveryAt":null,\
                "lastDeliveryStatus":null,"autoDisabledAt":null,"failedDays":0}]}""");
        SecurityContextHolder.clearContext();
    }

    @Test
    void exportListsPasskeyLabelsAndDatesButNoKeyMaterial() {
        final var user = User.builder().id(1L).email("test@example.com").authProvider("local")
                .password(PASSWORD_HASH).twoFactorSecret(TOTP_SECRET).webauthnUserHandle(USER_HANDLE)
                .twoFactorEnabled(true).twoStepEnabled(true).build();
        final var passkey = PasskeyCredential.builder()
                .id(5L)
                .user(user)
                .credentialId(CREDENTIAL_ID)
                .publicKey(PUBLIC_KEY)
                .attestationObject(ATTESTATION_OBJECT)
                .signCount(12L)
                .uvInitialized(true)
                .backupEligible(true)
                .backupState(true)
                .discoverable(true)
                .transports("hybrid,internal")
                .aaguid(AAGUID)
                .label("MacBook")
                .createdAt(LocalDateTime.of(2026, 10, 1, 9, 30))
                .lastUsedAt(LocalDateTime.of(2026, 10, 24, 18, 5))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("test@example.com", null));
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(userToUserDtoConverter.convert(user)).thenReturn(new UserToUserDtoConverter().convert(user));
        when(passkeyCredentialRepository.findByUserOrderByCreatedAtDesc(user)).thenReturn(List.of(passkey));
        when(twoFactorRecoveryCodeRepository.countByUserAndUsedFalse(user)).thenReturn(7L);

        final var mapper = JsonMapper.builder().build();
        final var json = mapper.writeValueAsString(userService.exportMyData());
        final var export = mapper.readTree(json);

        assertThat(export.at("/passkeys/0/label").asString()).isEqualTo("MacBook");
        assertThat(export.at("/passkeys/0/kind").asString()).isEqualTo("SYNCED");
        assertThat(export.get("passkeys").toString()).isEqualTo("""
                [{"label":"MacBook","kind":"SYNCED","backedUp":true,"createdAt":"2026-10-01T09:30:00","lastUsedAt":"2026-10-24T18:05:00"}]""");
        assertThat(export.get("recoveryCodesRemaining").isIntegralNumber()).isTrue();
        assertThat(export.get("recoveryCodesRemaining").asLong()).isEqualTo(7L);
        assertThat(json)
                .doesNotContain(CREDENTIAL_ID, PUBLIC_KEY, ATTESTATION_OBJECT, USER_HANDLE, AAGUID)
                .doesNotContain("\"credentialId\"", "\"publicKey\"", "\"attestationObject\"")
                .doesNotContain(PASSWORD_HASH, TOTP_SECRET, "$2a$", "codeHash")
                .doesNotContainPattern(RECOVERY_CODE_SHAPE);
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldUserHasLoggedIn() {
        final var user = User.builder().id(1L).build();
        when(userRepository.save(user)).thenReturn(user);

        userService.userHasLoggedIn(user);

        assertThat(user.getLastLogin()).isNotNull();
    }

    @Test
    void shouldCascadeDeactivationWhenUpdateDeactivatesUser() {
        final var updateDto = UserUpdateDto.builder().id(1L).active(false).build();
        final var existingUser = User.builder().id(1L).authProvider("local").active(true).build();
        final var deactivated = User.builder().id(1L).email("test@example.com").active(false).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userUpdateDtoToUserConverter.convert(updateDto)).thenReturn(deactivated);
        when(userRepository.deactivateById(1L)).thenReturn(1);

        userService.updateUser(updateDto);

        verify(sendingEmailService).sendEmailAccountDeactivated(existingUser);
    }

    @Test
    void anUpdateThatFindsTheUserAlreadyDeactivatedDoesNotCascadeAgain() {
        final var updateDto = UserUpdateDto.builder().id(1L).active(false).build();
        final var existingUser = User.builder().id(1L).email("test@example.com").authProvider("local").active(true).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userUpdateDtoToUserConverter.convert(updateDto))
                .thenReturn(User.builder().id(1L).email("test@example.com").active(false).build());
        when(userRepository.deactivateById(1L)).thenReturn(0);

        userService.updateUser(updateDto);

        verify(apiKeyRepository, never()).deactivateActiveByOwnerId(any());
        verify(connectedAppsService, never()).disconnectAll(any());
        verify(sendingEmailService, never()).sendEmailAccountDeactivated(any());
    }

    @Test
    void shouldNotCascadeWhenUpdateKeepsUserActive() {
        final var updateDto = UserUpdateDto.builder().id(1L).active(true).build();
        final var existingUser = User.builder().id(1L).authProvider("local").active(true).build();
        final var stillActive = User.builder().id(1L).active(true).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userUpdateDtoToUserConverter.convert(updateDto)).thenReturn(stillActive);
        when(userRepository.save(existingUser)).thenReturn(existingUser);

        userService.updateUser(updateDto);

        verify(sendingEmailService, org.mockito.Mockito.never()).sendEmailAccountDeactivated(any());
    }

    @Test
    void shouldSelfDeleteLocalAccountWithCorrectPassword() {
        final var user = User.builder().id(1L).email("test@example.com").authProvider("local").password("enc").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("test@example.com", null));
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        userService.deleteOwnAccount(DeleteAccountDto.builder().password("myPassword").build());

        verify(authValidator).passwordMatchesCurrentPassword(user, "myPassword");
        verify(resetTokenRepository).deleteByUser(user);
        verify(urlRepository).deleteByOwner(user);
        verify(passkeyCredentialRepository).deleteByUser(user);
        verify(bioAccountData).deleteFor(user);
        verify(notificationAccountData).deleteFor(user);
        verify(userRepository).delete(user);
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldSelfDeleteOauthAccountWithoutPassword() {
        final var user = User.builder().id(1L).email("g@example.com").authProvider("google").build();
        signedInMinutesAgo("g@example.com", 14);
        when(userRepository.findByEmail("g@example.com")).thenReturn(Optional.of(user));

        userService.deleteOwnAccount(new DeleteAccountDto());

        verify(authValidator, org.mockito.Mockito.never()).passwordMatchesCurrentPassword(any(), any());
        verify(userRepository).delete(user);
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldFailSelfDeleteWhenPasswordIncorrect() {
        final var user = User.builder().id(1L).email("test@example.com").authProvider("local").password("enc").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("test@example.com", null));
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        org.mockito.Mockito.doThrow(new NoAuthorizationException("Current password is incorrect"))
                .when(authValidator).passwordMatchesCurrentPassword(user, "wrong");

        assertThatThrownBy(() -> userService.deleteOwnAccount(DeleteAccountDto.builder().password("wrong").build()))
                .isInstanceOf(NoAuthorizationException.class);

        verify(userRepository, org.mockito.Mockito.never()).delete(any(User.class));
        SecurityContextHolder.clearContext();
    }

    @Test
    void anOAuthAccountCantDeleteItselfWithASignInOlderThan15Minutes() {
        final var user = User.builder().id(1L).email("g@example.com").authProvider("google").build();
        signedInMinutesAgo("g@example.com", 16);
        when(userRepository.findByEmail("g@example.com")).thenReturn(Optional.of(user));

        for (final var dto : List.of(new DeleteAccountDto(), DeleteAccountDto.builder().password("any password").build())) {
            assertThatThrownBy(() -> userService.deleteOwnAccount(dto))
                    .isInstanceOf(ReauthenticationRequiredException.class)
                    .hasMessage(SIGN_IN_AGAIN_TO_DELETE);
        }

        verify(userRepository, never()).delete(any(User.class));
        verifyNoInteractions(authValidator, connectedAppsService, urlRepository, passkeyCredentialRepository);
        SecurityContextHolder.clearContext();
    }

    @Test
    void aBlankPasswordCantDeleteALocalAccount() {
        final var user = User.builder().id(1L).email("test@example.com").authProvider("local").password("enc").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("test@example.com", null));
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.deleteOwnAccount(new DeleteAccountDto()))
                .isInstanceOf(ApiException.class)
                .hasMessage("Enter your current password.");

        verify(userRepository, never()).delete(any(User.class));
        verifyNoInteractions(authValidator);
        SecurityContextHolder.clearContext();
    }

    @Test
    void changingThePasswordAndDeletingTheAccountShareOneWrongPasswordCounter() {
        final var user = User.builder().id(1L).email("test@example.com").authProvider("local").password("enc").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("test@example.com", null));
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        org.mockito.Mockito.doThrow(new NoAuthorizationException(WRONG_PASSWORD))
                .when(authValidator).passwordMatchesCurrentPassword(user, "wrong");
        final var wrongPasswordChange = UpdatePasswordDto.builder().oldPassword("wrong").newPassword("newPassword").build();
        final var wrongDelete = DeleteAccountDto.builder().password("wrong").build();

        IntStream.rangeClosed(1, 10).forEach(attempt -> {
            assertThatThrownBy(() -> userService.updatePassword(wrongPasswordChange)).hasMessage(WRONG_PASSWORD);
            assertThatThrownBy(() -> userService.deleteOwnAccount(wrongDelete)).hasMessage(WRONG_PASSWORD);
        });

        assertThatThrownBy(() -> userService.updatePassword(wrongPasswordChange))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage(TOO_MANY_WRONG_PASSWORDS);
        assertThatThrownBy(() -> userService.deleteOwnAccount(DeleteAccountDto.builder().password("myPassword").build()))
                .isInstanceOf(NoAuthorizationException.class)
                .hasMessage(TOO_MANY_WRONG_PASSWORDS);
        verify(authValidator, times(20)).passwordMatchesCurrentPassword(user, "wrong");
        verify(authValidator, never()).passwordMatchesCurrentPassword(user, "myPassword");
        verify(passwordEncoder, never()).encode(any());
        verify(userRepository, never()).delete(any(User.class));
        assertThat(loginAttemptService.getLoginAttempts()).containsEntry("reauth:1", 20);
        SecurityContextHolder.clearContext();
    }

    @Test
    void aSuccessfulPasswordChangeResetsTheWrongPasswordCounter() {
        final var user = User.builder().id(1L).email("test@example.com").authProvider("local").password("enc").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("test@example.com", null));
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        org.mockito.Mockito.doThrow(new NoAuthorizationException(WRONG_PASSWORD))
                .when(authValidator).passwordMatchesCurrentPassword(user, "wrong");
        IntStream.rangeClosed(1, 19).forEach(attempt -> assertThatThrownBy(() -> userService.deleteOwnAccount(
                DeleteAccountDto.builder().password("wrong").build())).hasMessage(WRONG_PASSWORD));
        assertThat(loginAttemptService.getLoginAttempts()).containsEntry("reauth:1", 19);

        userService.updatePassword(UpdatePasswordDto.builder().oldPassword("oldPassword").newPassword("newPassword").build());

        assertThat(loginAttemptService.getLoginAttempts()).doesNotContainKey("reauth:1");
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldDisconnectConnectedAppsWhenAdminDeletesUser() {
        final var user = User.builder().id(1L).email("test@example.com").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        userService.deleteUserById(1L);

        verify(connectedAppsService).disconnectAll("test@example.com");
    }

    @Test
    void shouldDisconnectConnectedAppsWhenUserDeletesOwnAccount() {
        final var user = User.builder().id(1L).email("g@example.com").authProvider("google").build();
        signedInMinutesAgo("g@example.com", 1);
        when(userRepository.findByEmail("g@example.com")).thenReturn(Optional.of(user));

        userService.deleteOwnAccount(new DeleteAccountDto());

        verify(connectedAppsService).disconnectAll("g@example.com");
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldKeepConnectedAppsWhenSelfDeleteIsRefused() {
        final var user = User.builder().id(1L).email("test@example.com").authProvider("local").password("enc").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("test@example.com", null));
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        org.mockito.Mockito.doThrow(new NoAuthorizationException("Current password is incorrect"))
                .when(authValidator).passwordMatchesCurrentPassword(user, "wrong");

        assertThatThrownBy(() -> userService.deleteOwnAccount(DeleteAccountDto.builder().password("wrong").build()))
                .isInstanceOf(NoAuthorizationException.class);

        verifyNoInteractions(connectedAppsService, bioAccountData, notificationAccountData);
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldDisconnectConnectedAppsWhenDeactivatingUnusedAccount() {
        final var user = User.builder().id(1L).email("idle@example.com").active(true).build();
        when(appProperties.getDeactivateUserAccountAfterDays()).thenReturn(30L);
        when(userRepository.findByLastLoginIsLessThanEqualAndActiveTrue(any(java.time.LocalDateTime.class)))
                .thenReturn(Collections.singletonList(user));
        when(userRepository.saveAll(any())).thenReturn(Collections.singletonList(user));

        userService.deactivateUnusedUserAccounts();

        verify(connectedAppsService).disconnectAll("idle@example.com");
    }

    @Test
    void shouldDisconnectConnectedAppsWhenAdminDeactivatesUser() {
        final var updateDto = UserUpdateDto.builder().id(1L).active(false).build();
        final var existingUser = User.builder().id(1L).email("test@example.com").authProvider("local").active(true).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userUpdateDtoToUserConverter.convert(updateDto))
                .thenReturn(User.builder().id(1L).email("test@example.com").active(false).build());
        when(userRepository.deactivateById(1L)).thenReturn(1);

        userService.updateUser(updateDto);

        verify(connectedAppsService).disconnectAll("test@example.com");
    }

    @Test
    void shouldDisconnectConnectedAppsUnderPreviousEmailWhenAdminChangesEmail() {
        final var updateDto = UserUpdateDto.builder().id(1L).email("new@example.com").build();
        final var existingUser = User.builder().id(1L).email("old@example.com").authProvider("local").active(true).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userUpdateDtoToUserConverter.convert(updateDto))
                .thenReturn(User.builder().id(1L).email("new@example.com").active(true).build());
        when(userRepository.save(existingUser)).thenReturn(existingUser);

        userService.updateUser(updateDto);

        verify(connectedAppsService).disconnectAll("old@example.com");
        verify(connectedAppsService, org.mockito.Mockito.never()).disconnectAll("new@example.com");
    }

    @Test
    void shouldKeepConnectedAppsWhenUpdateKeepsEmailAndActive() {
        final var updateDto = UserUpdateDto.builder().id(1L).email("test@example.com").apiKeySlots(6L).active(true).build();
        final var existingUser = User.builder().id(1L).email("test@example.com").authProvider("local").active(true).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userUpdateDtoToUserConverter.convert(updateDto))
                .thenReturn(User.builder().id(1L).email("test@example.com").apiKeySlots(6L).active(true).build());
        when(userRepository.save(existingUser)).thenReturn(existingUser);

        userService.updateUser(updateDto);

        verifyNoInteractions(connectedAppsService);
    }
    private void signedInMinutesAgo(final String email, final long minutes) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(email, SIGNED_IN_TOKEN));
        when(tokenProvider.issuedAt(SIGNED_IN_TOKEN)).thenReturn(Optional.of(NOW.minus(Duration.ofMinutes(minutes))));
    }

    private AnnotationConfigApplicationContext signUpEmailsContext() {
        final var context = new AnnotationConfigApplicationContext();
        context.registerBean(TransactionalEventListenerFactory.class);
        context.registerBean(SignUpEmailListener.class, () -> new SignUpEmailListener(sendingEmailService));
        context.refresh();
        return context;
    }

    private static final class NoResourceTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(final Object transaction, final TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(final DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(final DefaultTransactionStatus status) {
        }

    }

    private static Pageable newestFirst(final Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Order.desc("createDate"), Sort.Order.desc("id")));
    }

}
