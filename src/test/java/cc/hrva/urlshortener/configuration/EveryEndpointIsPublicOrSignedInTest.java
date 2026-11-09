package cc.hrva.urlshortener.configuration;

import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.AnyHostIsPrimary;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.OAuth2LoginUserService;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.security.UnknownPageFilter;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = EveryEndpointIsPublicOrSignedInTest.TestApplication.class,
        properties = {
                "spring.liquibase.change-log=classpath:db/changelog/017-oauth2-authorization-server.xml",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=http://localhost"
        })
class EveryEndpointIsPublicOrSignedInTest {

    private static final RSAKey SIGNING_KEY = generateSigningKey();
    private static final String BASE_PACKAGE = "cc.hrva.urlshortener";
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[^}/]+}");
    private static final AnonymousAuthenticationToken ANONYMOUS = new AnonymousAuthenticationToken("test", "anonymousUser",
            AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

    private static final Set<String> PUBLIC = Set.of(
            "GET /",
            "GET /{short}",
            "GET /{short}/",
            "GET /favicon.ico",
            "GET /@{handle:.+}",
            "GET /error",
            "GET /.well-known/security.txt",
            "GET /.well-known/openai-apps-challenge",
            "GET /api/changelog",
            "GET /api/v1/public/settings",
            "GET /api/v1/public/stats",
            "GET /api/v1/public/alias-check",
            "GET /api/v1/public/bio-pages/{handle}",
            "POST /api/v1/public/bio-pages/{handle}/views",
            "POST /api/v1/reports",
            "GET /api/v1/notifications/unsubscribe",
            "POST /api/v1/notifications/unsubscribe",
            "POST /api/v1/urls",
            "GET /api/v1/urls/{short}",
            "GET /api/v1/urls/{short}/peek",
            "POST /api/v1/urls/{short}/unlock",
            "GET /api/v1/urls/{short}/preview",
            "GET /api/v1/urls/{short}/qr",
            "POST /api/v1/auth/register",
            "POST /api/v1/auth/login",
            "POST /api/v1/auth/password-reset",
            "POST /api/v1/auth/password-reset/confirm",
            "POST /api/v1/auth/verify-email/confirm",
            "POST /api/v1/auth/verify-email/resend",
            "POST /api/v1/auth/oauth/exchange",
            "POST /api/v1/auth/passkeys/options",
            "POST /api/v1/auth/passkeys/verify",
            "POST /api/v1/auth/2fa/totp",
            "POST /api/v1/auth/2fa/webauthn/options",
            "POST /api/v1/auth/2fa/webauthn/verify",
            "GET /oauth2/connect/continue"
    );

    @Autowired
    private FilterChainProxy springSecurityFilterChain;

    @MockitoBean
    private ConnectRequestStore connectRequestStore;

    @MockitoBean
    private RateLimiter rateLimiter;

    @MockitoBean
    private TokenProvider tokenProvider;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    @MockitoBean
    private OAuth2LoginUserService oAuth2LoginUserService;

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @Test
    void everyEndpointIsOnThePublicListOrNeedsASignedInCaller() {
        final var endpoints = endpoints();
        final var wronglyPublic = new TreeSet<String>();
        final var wronglySignedIn = new TreeSet<String>();

        endpoints.forEach(endpoint -> {
            final var anonymousAllowed = anonymousAllowed(endpoint);
            if (PUBLIC.contains(endpoint) && !anonymousAllowed) {
                wronglySignedIn.add(endpoint);
            }
            if (!PUBLIC.contains(endpoint) && anonymousAllowed) {
                wronglyPublic.add(endpoint);
            }
        });

        assertThat(endpoints).hasSizeGreaterThan(150);
        assertThat(wronglyPublic).as("endpoints anyone can call that aren't on the public list").isEmpty();
        assertThat(wronglySignedIn).as("endpoints on the public list that need a signed-in caller").isEmpty();
    }

    @Test
    void everyPublicEntryIsARealEndpoint() {
        assertThat(endpoints()).containsAll(PUBLIC);
    }

    @Test
    void noEndpointIsAnsweredAsAnUnknownPage() {
        final var answeredAsUnknown = endpoints().stream()
                .filter(endpoint -> {
                    final var methodAndPath = endpoint.split(" ", 2);
                    return UnknownPageFilter.isUnknownPage(methodAndPath[0], sample(methodAndPath[1]));
                })
                .toList();

        assertThat(answeredAsUnknown).isEmpty();
    }

    @Test
    void aHeadRequestIsAsPublicAsItsGet() {
        assertThat(anonymousAllowed("HEAD /{short}")).isTrue();
        assertThat(anonymousAllowed("HEAD /{short}/")).isTrue();
        assertThat(anonymousAllowed("HEAD /api/v1/urls/{short}")).isTrue();
        assertThat(anonymousAllowed("HEAD /api/v1/users")).isFalse();
    }

    @Test
    void theSignInRedirectsOfGoogleAndGithubArePublic() {
        assertThat(anonymousAllowed("GET /oauth2/authorization/google")).isTrue();
        assertThat(anonymousAllowed("GET /login/oauth2/code/github")).isTrue();
        assertThat(anonymousAllowed("GET /actuator/health")).isTrue();
        assertThat(anonymousAllowed("GET /actuator/health/liveness")).isTrue();
        assertThat(anonymousAllowed("GET /actuator/health/readiness")).isTrue();
        assertThat(anonymousAllowed("GET /actuator/health/db")).isFalse();
        assertThat(anonymousAllowed("GET /actuator")).isFalse();
        assertThat(anonymousAllowed("GET /actuator/env")).isFalse();
    }

    @Test
    void theSignedInPagesUnderThePublicLinkLookupStaySignedIn() {
        assertThat(anonymousAllowed("GET /api/v1/urls/export")).isFalse();
        assertThat(anonymousAllowed("GET /api/v1/urls/tags")).isFalse();
        assertThat(anonymousAllowed("GET /api/v1/urls/all")).isFalse();
        assertThat(anonymousAllowed("PUT /api/v1/urls/{id}")).isFalse();
        assertThat(anonymousAllowed("DELETE /api/v1/urls/{id}")).isFalse();
        assertThat(anonymousAllowed("POST /api/v1/urls/bulk")).isFalse();
    }

    @Test
    void anApiKeyReachesTheLinkEditAndNothingElseThatNeedsASignIn() {
        assertThat(anonymousAllowed("PUT /api/v1/urls/{id}", "hrva_key")).isTrue();
        assertThat(anonymousAllowed("PUT /api/v1/urls/{id}", " ")).isFalse();
        assertThat(anonymousAllowed("DELETE /api/v1/urls/{id}", "hrva_key")).isFalse();
        assertThat(anonymousAllowed("PATCH /api/v1/urls/{id}/activate", "hrva_key")).isFalse();
        assertThat(anonymousAllowed("GET /api/v1/urls/{id}/destination-change", "hrva_key")).isFalse();
        assertThat(anonymousAllowed("PUT /api/v1/users", "hrva_key")).isFalse();
    }

    private boolean anonymousAllowed(final String endpoint) {
        return anonymousAllowed(endpoint, null);
    }

    private boolean anonymousAllowed(final String endpoint, final String apiKey) {
        final var methodAndPath = endpoint.split(" ", 2);
        final var request = new MockHttpServletRequest(methodAndPath[0], sample(methodAndPath[1]));
        if (apiKey != null) {
            request.addHeader("X-Api-Key", apiKey);
        }
        final var chain = springSecurityFilterChain.getFilterChains().stream()
                .filter(candidate -> candidate.matches(request))
                .findFirst()
                .orElseThrow();
        final var authorization = chain.getFilters().stream()
                .filter(AuthorizationFilter.class::isInstance)
                .map(AuthorizationFilter.class::cast)
                .findFirst()
                .orElseThrow();
        return authorization.getAuthorizationManager().authorize(() -> ANONYMOUS, request).isGranted();
    }

    private static String sample(final String pattern) {
        return PATH_VARIABLE.matcher(pattern.replace("{handle:.+}", "ana")).replaceAll("x1");
    }

    private static List<String> endpoints() {
        final var mapping = new MappingReader();
        final var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        final var endpoints = new ArrayList<String>();
        scanner.findCandidateComponents(BASE_PACKAGE).stream()
                .map(candidate -> ClassUtils.resolveClassName(candidate.getBeanClassName(), null))
                .filter(EveryEndpointIsPublicOrSignedInTest::isMainCode)
                .forEach(controller -> ReflectionUtils.doWithMethods(controller, method -> {
                    final var info = mapping.mappingOf(method, controller);
                    if (info != null) {
                        info.getPatternValues().forEach(pattern -> methodsOf(info).forEach(verb -> endpoints.add(verb + " " + pattern)));
                    }
                }));
        return endpoints;
    }

    private static boolean isMainCode(final Class<?> type) {
        return !type.getProtectionDomain().getCodeSource().getLocation().getPath().contains("test-classes");
    }

    private static List<String> methodsOf(final RequestMappingInfo info) {
        final var methods = info.getMethodsCondition().getMethods();
        return methods.isEmpty() ? List.of("GET", "POST", "PUT", "PATCH", "DELETE") : methods.stream().map(RequestMethod::name).toList();
    }

    private static RSAKey generateSigningKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-signing-key").generate();
        } catch (final JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class MappingReader extends RequestMappingHandlerMapping {

        private MappingReader() {
            setEmbeddedValueResolver(value -> value.replace("${server.error.path:${error.path:/error}}", "/error"));
        }

        private RequestMappingInfo mappingOf(final Method method, final Class<?> controller) {
            return getMappingForMethod(method, controller);
        }

    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            AppConfiguration.class,
            WebConfiguration.class,
            AnyHostIsPrimary.class,
            JacksonConfiguration.class,
            SecurityConfiguration.class,
            AuthorizationServerConfiguration.class,
            McpResourceServerConfiguration.class,
            JwtFilter.class,
            ClientIpResolver.class
    })
    static class TestApplication {
    }

}
