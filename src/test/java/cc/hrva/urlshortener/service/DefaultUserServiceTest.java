package cc.hrva.urlshortener.service;

import java.util.Collections;
import java.util.Optional;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.converter.UserToUserDtoConverter;
import cc.hrva.urlshortener.converter.UserUpdateDtoToUserConverter;
import cc.hrva.urlshortener.dto.DeleteAccountDto;
import cc.hrva.urlshortener.dto.PasswordResetDto;
import cc.hrva.urlshortener.dto.RequestPasswordResetDto;
import cc.hrva.urlshortener.dto.UpdatePasswordDto;
import cc.hrva.urlshortener.dto.UserDto;
import cc.hrva.urlshortener.dto.UserSearchDto;
import cc.hrva.urlshortener.dto.UserUpdateDto;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.UserNotFoundException;
import cc.hrva.urlshortener.mcp.apps.ConnectedAppsService;
import cc.hrva.urlshortener.model.ApiKey;
import cc.hrva.urlshortener.model.ResetToken;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.ApiKeyRepository;
import cc.hrva.urlshortener.repository.IPAddressRepository;
import cc.hrva.urlshortener.repository.ResetTokenRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultUserServiceTest {

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
    private IPAddressRepository ipAddressRepository;

    @Mock
    private ResetTokenService resetTokenService;

    @Mock
    private ResetTokenRepository resetTokenRepository;

    @Mock
    private cc.hrva.urlshortener.repository.TwoFactorRecoveryCodeRepository twoFactorRecoveryCodeRepository;

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

    @BeforeEach
    void setUp() {
        this.userService = new DefaultUserService(appProperties, authValidator, urlRepository, userRepository,
                passwordEncoder, apiKeyRepository, emailLogRepository, ipAddressRepository, resetTokenService,
                resetTokenRepository, twoFactorRecoveryCodeRepository, userToUserDtoConverter, sendingEmailService,
                verificationTokenService, verificationTokenRepository, userUpdateDtoToUserConverter, connectedAppsService);
        lenient().when(userRepository.findByEmail(null)).thenReturn(Optional.empty());
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
        when(userRepository.save(user)).thenReturn(user);

        final var result = userService.register(user);

        assertThat(result.getEmail()).isEqualTo("test@example.com");
        verify(verificationTokenService).createTokenForUser(user);
        verify(sendingEmailService).sendVerificationEmail(eq(user), any());
        verify(sendingEmailService).sendNewUserNotificationToAdmin(user);
        verify(sendingEmailService, org.mockito.Mockito.never()).sendWelcomeEmail(any());
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
    void shouldFetchUserFromEmail() {
        final var user = User.builder().id(1L).email("test@example.com").active(true).build();
        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));

        final var result = userService.fetchUserFromEmail("test@example.com");

        assertThat(result).isEqualTo(user);
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
        when(userRepository.findAll(pageable)).thenReturn(users);

        assertThat(userService.fetchAllUsers(pageable, new UserSearchDto())).isEqualTo(users);
    }

    @Test
    void shouldFetchAllUsersWithSearchFilter() {
        final var pageable = PageRequest.of(0, 20);
        final var user = User.builder().id(1L).email("test@example.com").build();
        final var users = new PageImpl<>(Collections.singletonList(user));
        final var search = new UserSearchDto();
        search.setSearch("example");

        when(userRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(users);

        assertThat(userService.fetchAllUsers(pageable, search)).isEqualTo(users);
    }

    @Test
    void shouldFetchAllUsersWithActiveFilter() {
        final var pageable = PageRequest.of(0, 20);
        final var user = User.builder().id(1L).email("test@example.com").active(true).build();
        final var users = new PageImpl<>(Collections.singletonList(user));
        final var search = new UserSearchDto();
        search.setActive(true);

        when(userRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(users);

        assertThat(userService.fetchAllUsers(pageable, search)).isEqualTo(users);
    }

    @Test
    void shouldDeleteUserByIdWithOwnedData() {
        final var user = User.builder().id(1L).email("test@example.com").build();
        final var urls = Collections.singletonList(cc.hrva.urlshortener.model.Url.builder().id(7L).build());
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(urlRepository.findByOwner(user)).thenReturn(urls);

        userService.deleteUserById(1L);

        verify(ipAddressRepository).deleteByUrlIn(urls);
        verify(resetTokenRepository).deleteByUser(user);
        verify(urlRepository).deleteByOwner(user);
        verify(userRepository).delete(user);
    }

    @Test
    void shouldDeleteUserByIdWithoutUrlsSkipsIpAddressCleanup() {
        final var user = User.builder().id(1L).email("test@example.com").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(urlRepository.findByOwner(user)).thenReturn(Collections.emptyList());

        userService.deleteUserById(1L);

        verify(ipAddressRepository, org.mockito.Mockito.never()).deleteByUrlIn(any());
        verify(userRepository).delete(user);
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
        when(userRepository.save(user)).thenReturn(user);

        final var result = userService.updateUser(updateDto);

        assertThat(result).isEqualTo(user);
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
        when(apiKeyRepository.findByOwnerAndActiveTrue(user)).thenReturn(Collections.emptyList());

        userService.deactivateUnusedUserAccounts();

        assertThat(user.getActive()).isFalse();
        verify(sendingEmailService).sendEmailAccountDeactivated(user);
    }

    @Test
    void shouldRevokeApiKeysWhenDeactivatingUnusedAccount() {
        final var user = User.builder().id(1L).active(true).build();
        final var key = ApiKey.builder().id(5L).active(true).owner(user).build();
        when(appProperties.getDeactivateUserAccountAfterDays()).thenReturn(30L);
        when(userRepository.findByLastLoginIsLessThanEqualAndActiveTrue(any(java.time.LocalDateTime.class)))
                .thenReturn(Collections.singletonList(user));
        when(userRepository.saveAll(any())).thenReturn(Collections.singletonList(user));
        when(apiKeyRepository.findByOwnerAndActiveTrue(user)).thenReturn(Collections.singletonList(key));

        userService.deactivateUnusedUserAccounts();

        assertThat(key.isActive()).isFalse();
        verify(apiKeyRepository).saveAll(Collections.singletonList(key));
    }

    @Test
    void deactivatingAUserSavesItInactiveRevokesItsKeysDisconnectsItsAppsAndTellsIt() {
        final var user = User.builder().id(1L).email("scammer@example.com").active(true).build();
        final var key = ApiKey.builder().id(5L).active(true).owner(user).build();
        when(apiKeyRepository.findByOwnerAndActiveTrue(user)).thenReturn(Collections.singletonList(key));

        userService.deactivate(user);

        assertThat(user.getActive()).isFalse();
        verify(userRepository).save(user);
        assertThat(key.isActive()).isFalse();
        verify(apiKeyRepository).saveAll(Collections.singletonList(key));
        verify(connectedAppsService).disconnectAll("scammer@example.com");
        verify(sendingEmailService).sendEmailAccountDeactivated(user);
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
        assertThat(export.exportedAt()).isNotNull();
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
        when(userRepository.save(deactivated)).thenReturn(deactivated);
        when(apiKeyRepository.findByOwnerAndActiveTrue(deactivated)).thenReturn(Collections.emptyList());

        userService.updateUser(updateDto);

        verify(sendingEmailService).sendEmailAccountDeactivated(deactivated);
    }

    @Test
    void shouldNotCascadeWhenUpdateKeepsUserActive() {
        final var updateDto = UserUpdateDto.builder().id(1L).active(true).build();
        final var existingUser = User.builder().id(1L).authProvider("local").active(true).build();
        final var stillActive = User.builder().id(1L).active(true).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userUpdateDtoToUserConverter.convert(updateDto)).thenReturn(stillActive);
        when(userRepository.save(stillActive)).thenReturn(stillActive);

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
        verify(userRepository).delete(user);
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldSelfDeleteOauthAccountWithoutPassword() {
        final var user = User.builder().id(1L).email("g@example.com").authProvider("google").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("g@example.com", null));
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
    void shouldDisconnectConnectedAppsWhenAdminDeletesUser() {
        final var user = User.builder().id(1L).email("test@example.com").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(urlRepository.findByOwner(user)).thenReturn(Collections.emptyList());

        userService.deleteUserById(1L);

        verify(connectedAppsService).disconnectAll("test@example.com");
    }

    @Test
    void shouldDisconnectConnectedAppsWhenUserDeletesOwnAccount() {
        final var user = User.builder().id(1L).email("g@example.com").authProvider("google").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("g@example.com", null));
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

        verifyNoInteractions(connectedAppsService);
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldDisconnectConnectedAppsWhenDeactivatingUnusedAccount() {
        final var user = User.builder().id(1L).email("idle@example.com").active(true).build();
        when(appProperties.getDeactivateUserAccountAfterDays()).thenReturn(30L);
        when(userRepository.findByLastLoginIsLessThanEqualAndActiveTrue(any(java.time.LocalDateTime.class)))
                .thenReturn(Collections.singletonList(user));
        when(userRepository.saveAll(any())).thenReturn(Collections.singletonList(user));
        when(apiKeyRepository.findByOwnerAndActiveTrue(user)).thenReturn(Collections.emptyList());

        userService.deactivateUnusedUserAccounts();

        verify(connectedAppsService).disconnectAll("idle@example.com");
    }

    @Test
    void shouldDisconnectConnectedAppsWhenAdminDeactivatesUser() {
        final var updateDto = UserUpdateDto.builder().id(1L).active(false).build();
        final var existingUser = User.builder().id(1L).email("test@example.com").authProvider("local").active(true).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userUpdateDtoToUserConverter.convert(updateDto)).thenAnswer(invocation -> {
            existingUser.setActive(false);
            return existingUser;
        });
        when(userRepository.save(existingUser)).thenReturn(existingUser);
        when(apiKeyRepository.findByOwnerAndActiveTrue(existingUser)).thenReturn(Collections.emptyList());

        userService.updateUser(updateDto);

        verify(connectedAppsService).disconnectAll("test@example.com");
    }

    @Test
    void shouldDisconnectConnectedAppsUnderPreviousEmailWhenAdminChangesEmail() {
        final var updateDto = UserUpdateDto.builder().id(1L).email("new@example.com").build();
        final var existingUser = User.builder().id(1L).email("old@example.com").authProvider("local").active(true).build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser));
        when(userUpdateDtoToUserConverter.convert(updateDto)).thenAnswer(invocation -> {
            existingUser.setEmail("new@example.com");
            return existingUser;
        });
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
        when(userUpdateDtoToUserConverter.convert(updateDto)).thenAnswer(invocation -> {
            existingUser.setApiKeySlots(6L);
            return existingUser;
        });
        when(userRepository.save(existingUser)).thenReturn(existingUser);

        userService.updateUser(updateDto);

        verifyNoInteractions(connectedAppsService);
    }
}
