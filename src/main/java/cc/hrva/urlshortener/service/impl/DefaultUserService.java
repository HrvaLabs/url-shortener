package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.converter.UserToUserDtoConverter;
import cc.hrva.urlshortener.converter.UserUpdateDtoToUserConverter;
import cc.hrva.urlshortener.dto.AdminUserResponse;
import cc.hrva.urlshortener.dto.ApiKeyResponse;
import cc.hrva.urlshortener.dto.DataExportDto;
import cc.hrva.urlshortener.dto.DeleteAccountDto;
import cc.hrva.urlshortener.dto.PasswordResetDto;
import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.dto.RequestPasswordResetDto;
import cc.hrva.urlshortener.dto.UpdatePasswordDto;
import cc.hrva.urlshortener.dto.UserDto;
import cc.hrva.urlshortener.dto.UserSearchDto;
import cc.hrva.urlshortener.dto.UserUpdateDto;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.UserNotFoundException;
import cc.hrva.urlshortener.listener.UserRegisteredEvent;
import cc.hrva.urlshortener.mcp.apps.ConnectedAppsService;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.ApiKeyRepository;
import cc.hrva.urlshortener.repository.EmailLogRepository;
import cc.hrva.urlshortener.repository.PasskeyCredentialRepository;
import cc.hrva.urlshortener.repository.ResetTokenRepository;
import cc.hrva.urlshortener.repository.TwoFactorRecoveryCodeRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.repository.VerificationTokenRepository;
import cc.hrva.urlshortener.service.VerificationTokenService;
import cc.hrva.urlshortener.repository.specification.UserSpecification;
import cc.hrva.urlshortener.security.Reauthentication;
import cc.hrva.urlshortener.service.ResetTokenService;
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.service.UserService;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.service.bio.BioAccountData;
import cc.hrva.urlshortener.service.domain.AccountActiveChangedEvent;
import cc.hrva.urlshortener.service.domain.CustomDomainAccountData;
import cc.hrva.urlshortener.service.notification.NotificationAccountData;
import cc.hrva.urlshortener.util.EmailAddresses;
import cc.hrva.urlshortener.util.SortWhitelist;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DefaultUserService implements UserService {

    private static final String CREATE_DATE = "createDate";
    private static final SortWhitelist USER_SORTS = SortWhitelist.of("email", CREATE_DATE, "lastLogin", "active")
            .byDefault(Sort.by(Sort.Order.desc(CREATE_DATE)))
            .thenBy(Sort.Order.desc("id"));

    private final AppProperties appProperties;
    private final Reauthentication reauthentication;
    private final UrlRepository urlRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApiKeyRepository apiKeyRepository;
    private final EmailLogRepository emailLogRepository;
    private final ResetTokenService resetTokenService;
    private final ResetTokenRepository resetTokenRepository;
    private final TwoFactorRecoveryCodeRepository twoFactorRecoveryCodeRepository;
    private final PasskeyCredentialRepository passkeyCredentialRepository;
    private final BioAccountData bioAccountData;
    private final NotificationAccountData notificationAccountData;
    private final CustomDomainAccountData customDomainAccountData;
    private final UserToUserDtoConverter userToUserDtoConverter;
    private final DefaultSendingEmailService sendingEmailService;
    private final VerificationTokenService verificationTokenService;
    private final VerificationTokenRepository verificationTokenRepository;
    private final UserUpdateDtoToUserConverter userUpdateDtoToUserConverter;
    private final ConnectedAppsService connectedAppsService;
    private final ScammerSignalService scammerSignalService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public User register(final User user) {
        final var savedUser = userRepository.save(user);
        log.info("User registered email={}", savedUser.getEmail());

        final var verificationToken = verificationTokenService.createTokenForUser(savedUser);
        eventPublisher.publishEvent(new UserRegisteredEvent(savedUser, verificationToken));
        eventPublisher.publishEvent(new AdminAlertEvent.UserSignedUp(savedUser.getId(), savedUser.getAuthProvider()));

        return savedUser;
    }

    @Override
    public User getUserFromToken() {
        final var username = SecurityContextHolder.getContext().getAuthentication().getName();

        return userRepository.findByEmail(username).orElse(null);
    }

    @Override
    public UserDto fetchCurrentUser() {
        return Optional.ofNullable(getUserFromToken())
                .map(userToUserDtoConverter::convert)
                .orElseThrow(() -> new NoAuthorizationException("Invalid credentials"));
    }

    @Override
    public User fetchUserFromEmail(final String email) {
        log.info("User login email={}", EmailAddresses.masked(email));

        return fetchActiveUserByEmail(email);
    }

    @Override
    public User fetchActiveUserByEmail(final String email) {
        final var user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        if (Boolean.FALSE.equals(user.getActive())) {
            throw new UserNotFoundException("User is inactive, please contact administrator");
        }

        return user;
    }

    @Override
    @Transactional
    public void persistUser(final User user) {
        userRepository.save(user);
    }

    @Override
    public Page<AdminUserResponse> fetchAllUsers(final Pageable pageable, final UserSearchDto search) {
        final var sorted = USER_SORTS.apply(pageable);
        final var possibleScammers = scammerSignalService.possibleScammerIds();
        final var onlyPossibleScammers = Boolean.TRUE.equals(search.getPossibleScammer());
        if (onlyPossibleScammers && possibleScammers.isEmpty()) {
            return Page.empty(sorted);
        }

        final var spec = buildSpecification(search, onlyPossibleScammers ? possibleScammers : null);
        final var users = spec == null ? userRepository.findAll(sorted) : userRepository.findAll(spec, sorted);
        return users.map(user -> AdminUserResponse.from(user, possibleScammers.contains(user.getId())));
    }

    private Specification<User> buildSpecification(final UserSearchDto search, final Set<Long> possibleScammers) {
        Specification<User> spec = null;

        if (StringUtils.isNotEmpty(search.getSearch())) {
            spec = UserSpecification.search(search.getSearch());
        }
        if (search.getActive() != null) {
            spec = (spec == null) ? UserSpecification.hasActive(search.getActive()) : spec.and(UserSpecification.hasActive(search.getActive()));
        }
        if (possibleScammers != null) {
            spec = (spec == null) ? UserSpecification.idIn(possibleScammers) : spec.and(UserSpecification.idIn(possibleScammers));
        }

        return spec;
    }

    @Override
    @Transactional
    @CacheEvict(value = "urls", allEntries = true)
    public void deleteUserById(final Long id) {
        log.info("Delete user id={}", id);
        final var user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User not found"));
        deleteUserAndOwnedData(user);
    }

    @Override
    @Transactional
    @CacheEvict(value = "urls", allEntries = true)
    public void deleteOwnAccount(final DeleteAccountDto deleteAccountDto) {
        final var user = getUserFromToken();
        if (user == null) {
            throw new NoAuthorizationException("Not authenticated");
        }

        reauthentication.confirmAccountDeletion(user, deleteAccountDto.getPassword());

        log.info("Self-delete account id={}", user.getId());
        deleteUserAndOwnedData(user);
    }

    @Override
    public DataExportDto exportMyData() {
        final var user = getUserFromToken();
        if (user == null) {
            throw new NoAuthorizationException("Not authenticated");
        }
        log.info("Data export requested by user id={}", user.getId());

        final var urls = urlRepository.findByOwner(user).stream()
                .map(UrlResponse::from)
                .toList();
        final var apiKeys = apiKeyRepository.findByOwner(user).stream()
                .map(ApiKeyResponse::from)
                .toList();
        final var emails = emailLogRepository.findByRecipientContainingIgnoreCase(user.getEmail()).stream()
                .map(log -> new DataExportDto.EmailExport(log.getSubject(), log.getStatus(), log.getSentAt(), log.getCreatedAt()))
                .toList();
        final var passkeys = passkeyCredentialRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .map(DataExportDto.PasskeyExport::from)
                .toList();

        return new DataExportDto(
                LocalDateTime.now(),
                userToUserDtoConverter.convert(user),
                urls,
                apiKeys,
                emails,
                passkeys,
                twoFactorRecoveryCodeRepository.countByUserAndUsedFalse(user),
                bioAccountData.exportFor(user).orElse(null),
                notificationAccountData.exportFor(user),
                customDomainAccountData.exportFor(user));
    }

    private void deleteUserAndOwnedData(final User user) {
        resetTokenRepository.deleteByUser(user);
        verificationTokenRepository.deleteByUser(user);
        twoFactorRecoveryCodeRepository.deleteByUser(user);
        passkeyCredentialRepository.deleteByUser(user);
        bioAccountData.deleteFor(user);
        notificationAccountData.deleteFor(user);
        final var releasedDomains = customDomainAccountData.releaseFor(user);
        urlRepository.deleteByOwner(user);
        customDomainAccountData.purgeFor(releasedDomains);
        disconnectConnectedApps(user.getId(), user.getEmail());
        // API keys are removed via CascadeType.ALL on User.apiKeys when the user is deleted.
        userRepository.delete(user);
        log.info("Deleted user id={} and all owned data", user.getId());
    }

    @Override
    @Transactional
    public AdminUserResponse updateUser(final UserUpdateDto userUpdateDto) {
        log.info("Update user id={}", userUpdateDto.getId());

        final var existingUser = userRepository.findById(userUpdateDto.getId())
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        if (existingUser.getAuthProvider() != null && !"local".equals(existingUser.getAuthProvider())
                && userUpdateDto.getEmail() != null && !userUpdateDto.getEmail().equals(existingUser.getEmail())) {
            throw new ApiException("Email cannot be changed for %s accounts".formatted(existingUser.getAuthProvider()));
        }

        final var previousEmail = existingUser.getEmail();
        final var editedUser = Objects.requireNonNull(userUpdateDtoToUserConverter.convert(userUpdateDto));
        existingUser.setEmail(editedUser.getEmail());
        existingUser.setApiKeySlots(editedUser.getApiKeySlots());
        userRepository.save(existingUser);
        final var deactivated = Boolean.FALSE.equals(editedUser.getActive()) && userRepository.deactivateById(existingUser.getId()) == 1;
        if (Boolean.TRUE.equals(editedUser.getActive()) && userRepository.reactivateById(existingUser.getId()) == 1) {
            eventPublisher.publishEvent(new AccountActiveChangedEvent(List.of(existingUser.getId())));
        }
        final var updatedUser = userRepository.findById(existingUser.getId())
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        if (!Objects.equals(previousEmail, updatedUser.getEmail())) {
            disconnectConnectedApps(updatedUser.getId(), previousEmail);
        }
        if (deactivated) {
            log.info("Deactivated user with id {}", updatedUser.getId());
            cascadeDeactivation(updatedUser);
            eventPublisher.publishEvent(new AccountActiveChangedEvent(List.of(updatedUser.getId())));
        }

        return AdminUserResponse.from(updatedUser);
    }

    @Override
    @Transactional
    public User updatePassword(final UpdatePasswordDto updatePasswordDto) {
        final var user = getUserFromToken();
        reauthentication.confirmPassword(user, updatePasswordDto.getOldPassword());
        user.setPassword(passwordEncoder.encode(updatePasswordDto.getNewPassword()));
        log.info("Password changed for user id={}", user.getId());

        return userRepository.save(user);
    }

    @Override
    @Transactional
    public void sendPasswordResetLinkToUser(final RequestPasswordResetDto requestPasswordResetDto) {
        log.info("Password reset requested email={}", requestPasswordResetDto.getEmail());

        final var user = userRepository.findByEmail(requestPasswordResetDto.getEmail());

        if (user.isPresent()) {
            resetTokenService.deactivateActiveResetTokenIfExists(user.get());
            sendingEmailService.sendEmailForgotPassword(user.get(), resetTokenService.createTokenForUser(user.get()));
        }
    }

    @Override
    @Transactional
    public User resetPassword(final PasswordResetDto passwordResetDto) {
        log.info("Password reset for user email={}", passwordResetDto.getEmail());

        final var user = userRepository.findByEmail(passwordResetDto.getEmail())
                .orElseThrow(() -> new NoAuthorizationException("Invalid credentials"));

        final var token = resetTokenService.getResetTokenFromUserAndToken(user, passwordResetDto.getToken());

        if (token.isActive()) {
            user.setPassword(passwordEncoder.encode(passwordResetDto.getPassword()));
            resetTokenService.deactivateAndSaveToken(token);
            reauthentication.forgetWrongPasswords(user);

            return userRepository.save(user);
        }

        throw new NoAuthorizationException("Token expired");
    }

    @Override
    @Transactional
    public void deactivateUnusedUserAccounts() {
        final var users = userRepository.findByLastLoginIsLessThanEqualAndActiveTrue(LocalDateTime.now()
                .minusDays(appProperties.getDeactivateUserAccountAfterDays())).stream()
                .map(this::deactivateUser)
                .toList();

        userRepository.saveAll(users);
        if (!users.isEmpty()) {
            log.info("Deactivated {} users", users.size());
            eventPublisher.publishEvent(new AccountActiveChangedEvent(users.stream().map(User::getId).toList()));
        }
    }

    @Override
    @Transactional
    public void deactivate(final User user) {
        if (userRepository.deactivateById(user.getId()) == 0) {
            return;
        }
        log.info("Deactivated user with id {}", user.getId());
        cascadeDeactivation(user);
        eventPublisher.publishEvent(new AccountActiveChangedEvent(List.of(user.getId())));
    }

    @Override
    @Transactional
    public boolean reactivate(final long userId) {
        final var reactivated = userRepository.reactivateById(userId) == 1;
        if (reactivated) {
            eventPublisher.publishEvent(new AccountActiveChangedEvent(List.of(userId)));
        }
        return reactivated;
    }

    @Override
    @Transactional
    public void userHasLoggedIn(final User user) {
        user.userLoggedIn();
        persistUser(user);
    }

    private User deactivateUser(final User user) {
        user.setActive(false);
        log.info("Deactivated user with id {}", user.getId());
        cascadeDeactivation(user);

        return user;
    }

    private void cascadeDeactivation(final User user) {
        final var revokedKeys = apiKeyRepository.deactivateActiveByOwnerId(user.getId());
        if (revokedKeys > 0) {
            log.info("Revoked {} API keys for deactivated user id={}", revokedKeys, user.getId());
        }

        disconnectConnectedApps(user.getId(), user.getEmail());
        sendingEmailService.sendEmailAccountDeactivated(user);
    }

    private void disconnectConnectedApps(final Long userId, final String email) {
        final var authorizations = connectedAppsService.disconnectAll(email);
        if (authorizations > 0) {
            log.info("Removed {} MCP authorizations for user id={}", authorizations, userId);
        }
    }

}
