package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.codebook.Authorities;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.repository.UserSignInMethodRepository;
import cc.hrva.urlshortener.service.AuthoritiesService;
import cc.hrva.urlshortener.service.SignInMethodService;
import cc.hrva.urlshortener.service.impl.DefaultSignInMethodService;
import cc.hrva.urlshortener.service.settings.SettingsServiceFixtures;
import cc.hrva.urlshortener.service.twostep.PendingLoginStore;
import cc.hrva.urlshortener.service.twostep.TwoStepMethods;
import jakarta.validation.Validation;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = OAuth2SignInWritesTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.liquibase.enabled=true",
                "spring.datasource.url=jdbc:h2:mem:oauth2-sign-in-writes;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret"
        })
class OAuth2SignInWritesTest {

    private static final long MEMBER = 790L;
    private static final String MEMBER_EMAIL = "google-member@example.com";
    private static final String NEWCOMER_EMAIL = "google-newcomer@example.com";
    private static final long PRE_REGISTERED = 791L;
    private static final String PRE_REGISTERED_EMAIL = "pre-registered@example.com";
    private static final LocalDateTime LOADED_LOGIN = LocalDateTime.of(2026, 10, 1, 9, 0);

    private final AtomicBoolean concurrentChangeDone = new AtomicBoolean();

    private OAuth2LoginSuccessHandler handler;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private UserRepository userRepository;

    @Autowired
    private SignInMethodService signInMethodService;

    @BeforeEach
    void handlerOutsideAnyTransaction() {
        final var authoritiesService = mock(AuthoritiesService.class);
        final var tokenProvider = mock(TokenProvider.class);
        final var appProperties = mock(AppProperties.class);
        when(authoritiesService.getDefaultAuthority()).thenReturn(Authorities.builder().id(1L).name("ROLE_USER").build());
        when(tokenProvider.createToken(any())).thenReturn("jwt-token");
        when(appProperties.getFrontendUrl()).thenReturn("http://fe");
        when(appProperties.getUserApiKeySlots()).thenReturn(2L);
        handler = new OAuth2LoginSuccessHandler(userRepository, authoritiesService, tokenProvider, appProperties,
                Validation.buildDefaultValidatorFactory().getValidator(), mock(ApplicationEventPublisher.class),
                mock(PendingLoginStore.class), mock(TwoStepMethods.class), signInMethodService,
                SettingsServiceFixtures.defaults(new AppProperties()));
    }

    @AfterEach
    void removeTheAccounts() {
        jdbcTemplate.update("delete from user_account_authorities where user_id in (select id from user_account where email in (?, ?, ?))",
                MEMBER_EMAIL, NEWCOMER_EMAIL, PRE_REGISTERED_EMAIL);
        jdbcTemplate.update("delete from user_account where email in (?, ?, ?)", MEMBER_EMAIL, NEWCOMER_EMAIL, PRE_REGISTERED_EMAIL);
    }

    @Test
    void aSignInRecordsOnlyItsLastLoginAndKeepsAPasswordAndTwoStepChangeMadeAfterTheAccountWasLoaded() throws Exception {
        jdbcTemplate.update("""
                insert into user_account (id, email, password, active, two_step_enabled, auth_provider, last_login, email_verified)
                values (?, ?, 'secret-hash', true, false, 'google', ?, true)""", MEMBER, MEMBER_EMAIL, LOADED_LOGIN);
        jdbcTemplate.update("insert into user_account_authorities (user_id, authorities_id) values (?, 1)", MEMBER);
        doAnswer(invocation -> {
            final var loaded = mockingDetails(userRepository).getMockCreationSettings().getDefaultAnswer().answer(invocation);
            if (concurrentChangeDone.compareAndSet(false, true)) {
                CompletableFuture.runAsync(() -> jdbcTemplate.update(
                        "update user_account set password = 'changed-hash', two_step_enabled = true where id = ?", MEMBER)).join();
            }
            return loaded;
        }).when(userRepository).findByEmail(MEMBER_EMAIL);
        final var startedAt = LocalDateTime.now().minusSeconds(1);

        final var response = signIn(MEMBER_EMAIL);

        assertThat(concurrentChangeDone).isTrue();
        assertThat(response.getRedirectedUrl()).startsWith("http://fe/auth/callback?token=jwt-token");
        final var row = jdbcTemplate.queryForMap("select password, two_step_enabled, active, last_login from user_account where id = ?", MEMBER);
        assertThat(row)
                .containsEntry("PASSWORD", "changed-hash")
                .containsEntry("TWO_STEP_ENABLED", true)
                .containsEntry("ACTIVE", true);
        assertThat(((Timestamp) row.get("LAST_LOGIN")).toLocalDateTime()).isAfter(startedAt);
        assertThat(signInMethodsOf(MEMBER)).containsExactly(Map.entry("GOOGLE", row.get("LAST_LOGIN")));
    }

    @Test
    void aFirstSignInCreatesTheAccountAndRecordsItsLastLogin() throws Exception {
        final var startedAt = LocalDateTime.now().minusSeconds(1);

        final var response = signIn(NEWCOMER_EMAIL);

        assertThat(response.getRedirectedUrl()).startsWith("http://fe/auth/callback?token=jwt-token");
        final var row = jdbcTemplate.queryForMap("select id, auth_provider, active, last_login from user_account where email = ?", NEWCOMER_EMAIL);
        assertThat(row).containsEntry("AUTH_PROVIDER", "google").containsEntry("ACTIVE", true);
        assertThat(((Timestamp) row.get("LAST_LOGIN")).toLocalDateTime()).isAfter(startedAt);
        assertThat(jdbcTemplate.queryForObject("select count(*) from user_account_authorities where user_id = ? and authorities_id = 1",
                Long.class, row.get("ID"))).isOne();
        assertThat(signInMethodsOf((Long) row.get("ID"))).containsExactly(Map.entry("GOOGLE", row.get("LAST_LOGIN")));
    }

    @Test
    void aGitHubSignInToAnUnconfirmedPasswordAccountReplacesThePasswordAndConfirmsTheEmail() throws Exception {
        jdbcTemplate.update("""
                insert into user_account (id, email, password, active, two_step_enabled, auth_provider, email_verified)
                values (?, ?, 'pre-registered-hash', true, false, 'local', false)""", PRE_REGISTERED, PRE_REGISTERED_EMAIL);
        jdbcTemplate.update("insert into user_account_authorities (user_id, authorities_id) values (?, 1)", PRE_REGISTERED);

        final var response = signIn(PRE_REGISTERED_EMAIL, "github");

        assertThat(response.getRedirectedUrl()).startsWith("http://fe/auth/callback?token=jwt-token");
        final var row = jdbcTemplate.queryForMap("select password, email_verified, auth_provider from user_account where id = ?", PRE_REGISTERED);
        assertThat(row).containsEntry("EMAIL_VERIFIED", true).containsEntry("AUTH_PROVIDER", "local");
        assertThat((String) row.get("PASSWORD")).isNotEqualTo("pre-registered-hash").isNotBlank();
        assertThat(signInMethodsOf(PRE_REGISTERED)).containsOnlyKeys("GITHUB");
    }

    private Map<String, Object> signInMethodsOf(final Long userId) {
        final var methods = new LinkedHashMap<String, Object>();
        jdbcTemplate.query("select method, last_used_at from user_sign_in_method where user_id = ? order by method",
                (rs, rowNum) -> methods.put(rs.getString(1), rs.getTimestamp(2)), userId);
        return methods;
    }

    private MockHttpServletResponse signIn(final String email) throws Exception {
        return signIn(email, "google");
    }

    private MockHttpServletResponse signIn(final String email, final String provider) throws Exception {
        final var principal = new DefaultOAuth2User(List.of(), Map.of("email", email, "email_verified", true, "sub", email), "sub");
        final var response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, new OAuth2AuthenticationToken(principal, List.of(), provider));
        return response;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = Url.class)
    @EnableJpaRepositories(
            basePackageClasses = UrlRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {UrlRepository.class, UserRepository.class, UserSignInMethodRepository.class}))
    @Import(DefaultSignInMethodService.class)
    static class TestApplication {
    }

}
