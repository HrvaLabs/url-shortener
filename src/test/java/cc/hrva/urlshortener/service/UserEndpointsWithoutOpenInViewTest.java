package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.converter.UserToUserDtoConverter;
import cc.hrva.urlshortener.converter.UserUpdateDtoToUserConverter;
import cc.hrva.urlshortener.dto.PasswordResetDto;
import cc.hrva.urlshortener.dto.UpdatePasswordDto;
import cc.hrva.urlshortener.dto.UserSearchDto;
import cc.hrva.urlshortener.dto.UserUpdateDto;
import cc.hrva.urlshortener.mcp.apps.ConnectedAppsService;
import cc.hrva.urlshortener.model.ResetToken;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.repository.ApiKeyRepository;
import cc.hrva.urlshortener.repository.EmailLogRepository;
import cc.hrva.urlshortener.repository.PasskeyCredentialRepository;
import cc.hrva.urlshortener.repository.ResetTokenRepository;
import cc.hrva.urlshortener.repository.TwoFactorRecoveryCodeRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.repository.UserSignInMethodRepository;
import cc.hrva.urlshortener.repository.VerificationTokenRepository;
import cc.hrva.urlshortener.security.Reauthentication;
import cc.hrva.urlshortener.service.bio.BioAccountData;
import cc.hrva.urlshortener.service.domain.CustomDomainAccountData;
import cc.hrva.urlshortener.service.notification.NotificationAccountData;
import cc.hrva.urlshortener.service.impl.DefaultSendingEmailService;
import cc.hrva.urlshortener.service.impl.DefaultSignInMethodService;
import cc.hrva.urlshortener.service.impl.DefaultUserService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = UserEndpointsWithoutOpenInViewTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.jpa.open-in-view=false",
                "spring.datasource.url=jdbc:h2:mem:user-endpoints-without-open-in-view;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class UserEndpointsWithoutOpenInViewTest {

    private static final long MEMBER = 700L;
    private static final String MEMBER_EMAIL = "member@example.com";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AppProperties appProperties;

    @MockitoBean
    private Reauthentication reauthentication;

    @MockitoBean
    private UrlRepository urlRepository;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private ApiKeyRepository apiKeyRepository;

    @MockitoBean
    private EmailLogRepository emailLogRepository;

    @MockitoBean
    private ResetTokenService resetTokenService;

    @MockitoBean
    private ResetTokenRepository resetTokenRepository;

    @MockitoBean
    private TwoFactorRecoveryCodeRepository twoFactorRecoveryCodeRepository;

    @MockitoBean
    private PasskeyCredentialRepository passkeyCredentialRepository;

    @MockitoBean
    private BioAccountData bioAccountData;

    @MockitoBean
    private NotificationAccountData notificationAccountData;

    @MockitoBean
    private CustomDomainAccountData customDomainAccountData;

    @MockitoBean
    private DefaultSendingEmailService sendingEmailService;

    @MockitoBean
    private VerificationTokenService verificationTokenService;

    @MockitoBean
    private VerificationTokenRepository verificationTokenRepository;

    @MockitoBean
    private ConnectedAppsService connectedAppsService;

    @MockitoBean
    private ScammerSignalService scammerSignalService;

    @BeforeEach
    void seedTheMember() {
        jdbcTemplate.update("insert into user_account (id, email, password, api_key_slots, active) values (?, ?, 'secret-hash', 4, true)",
                MEMBER, MEMBER_EMAIL);
        jdbcTemplate.update("insert into user_account_authorities (user_id, authorities_id) values (?, 1)", MEMBER);
        jdbcTemplate.update("""
                insert into user_sign_in_method (user_id, method, first_used_at, last_used_at)
                values (?, 'GITHUB', timestamp '2026-10-20 08:00:00', timestamp '2026-10-28 19:45:00')""", MEMBER);
        when(scammerSignalService.possibleScammerIds()).thenReturn(Set.of(MEMBER));
    }

    @AfterEach
    void removeTheMember() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.update("delete from user_account_authorities where user_id = ?", MEMBER);
        jdbcTemplate.update("delete from user_account where id = ?", MEMBER);
    }

    @Test
    void theUserListIsWrittenAfterItsTransactionWithTheRoles() {
        final var search = new UserSearchDto();
        search.setSearch(MEMBER_EMAIL);

        final var users = userService.fetchAllUsers(PageRequest.of(0, 20), search);

        final var json = JSON.readTree(JSON.writeValueAsString(users.getContent()));
        assertThat(json).hasSize(1);
        assertThat(json.get(0).get("email").asString()).isEqualTo(MEMBER_EMAIL);
        assertThat(json.get(0).get("authorities").get(0).get("name").asString()).isEqualTo("ROLE_USER");
        assertThat(json.get(0).get("possibleScammer").asBoolean()).isTrue();
        assertThat(json.get(0).get("signInMethods").toString())
                .isEqualTo("[{\"method\":\"GITHUB\",\"firstUsedAt\":\"2026-10-20T08:00:00\",\"lastUsedAt\":\"2026-10-28T19:45:00\"}]");
    }

    @Test
    void anEditedUserIsWrittenAfterItsTransactionWithTheRoles() {
        final var edit = UserUpdateDto.builder().id(MEMBER).apiKeySlots(7L).build();

        final var updated = userService.updateUser(edit);

        final var json = JSON.readTree(JSON.writeValueAsString(updated));
        assertThat(json.get("apiKeySlots").asLong()).isEqualTo(7L);
        assertThat(json.get("authorities").get(0).get("name").asString()).isEqualTo("ROLE_USER");
        assertThat(json.has("possibleScammer")).isFalse();
        assertThat(json.get("signInMethods").get(0).get("method").asString()).isEqualTo("GITHUB");
    }

    @Test
    void aChangedPasswordAnswerIsWrittenAfterItsTransactionWithTheRoles() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(MEMBER_EMAIL, null, List.of()));
        when(passwordEncoder.encode(anyString())).thenReturn("new-hash");

        final var answer = userService.updatePassword(new UpdatePasswordDto("old-password", "new-password"));

        final var json = JSON.readTree(JSON.writeValueAsString(answer));
        assertThat(json.get("email").asString()).isEqualTo(MEMBER_EMAIL);
        assertThat(json.get("authorities").get(0).get("name").asString()).isEqualTo("ROLE_USER");
        assertThat(json.has("password")).isFalse();
    }

    @Test
    void aResetPasswordAnswerIsWrittenAfterItsTransactionWithTheRoles() {
        when(passwordEncoder.encode(anyString())).thenReturn("new-hash");
        when(resetTokenService.getResetTokenFromUserAndToken(any(), anyString())).thenReturn(ResetToken.builder().token("reset-token").active(true).build());

        final var answer = userService.resetPassword(new PasswordResetDto("reset-token", MEMBER_EMAIL, "new-password"));

        final var json = JSON.readTree(JSON.writeValueAsString(answer));
        assertThat(json.get("email").asString()).isEqualTo(MEMBER_EMAIL);
        assertThat(json.get("authorities").get(0).get("name").asString()).isEqualTo("ROLE_USER");
        assertThat(json.has("password")).isFalse();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {UserRepository.class, UserSignInMethodRepository.class}))
    @Import({DefaultUserService.class, DefaultSignInMethodService.class, UserToUserDtoConverter.class, UserUpdateDtoToUserConverter.class})
    static class TestApplication {
    }

}
