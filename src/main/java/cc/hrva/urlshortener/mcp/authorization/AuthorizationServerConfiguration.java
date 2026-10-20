package cc.hrva.urlshortener.mcp.authorization;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.mcp.bridge.ConnectContinueController;
import cc.hrva.urlshortener.mcp.bridge.ConnectRedirectEntryPoint;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.mcp.bridge.SessionEndingAuthorizationResponseHandler;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.text.ParseException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springaicommunity.mcp.security.authorizationserver.config.LocalhostWildcardPortValidator;
import org.springaicommunity.mcp.security.authorizationserver.config.McpAuthorizationServerConfigurer;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientRegistrationAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator.DEFAULT_SCOPE_VALIDATOR;

@Configuration
@RequiredArgsConstructor
public class AuthorizationServerConfiguration {

    private static final String SIGNING_KEY_PROPERTY = "app.mcp.signing-key";
    private static final int MINIMUM_SIGNING_KEY_SIZE = 2048;
    private static final String AUTHORIZATION_ENDPOINT = "/oauth2/authorize";
    private static final String CLIENT_REGISTRATION_ENDPOINT = "/oauth2/register";
    private static final String OPENID_CONFIGURATION = "/.well-known/openid-configuration";
    private static final String PATH_SUFFIXED_AUTHORIZATION_SERVER_METADATA = "/.well-known/oauth-authorization-server/**";
    private static final String MCP_RESOURCE_PATH = "/mcp";

    private final AppProperties appProperties;

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(final HttpSecurity http,
                                                                      final RegisteredClientRepository registeredClientRepository,
                                                                      final AuthorizationServerSettings authorizationServerSettings,
                                                                      final ConnectRequestStore connectRequestStore) throws Exception {
        final var unsupportedDiscovery = new OrRequestMatcher(
                path(OPENID_CONFIGURATION),
                path(PATH_SUFFIXED_AUTHORIZATION_SERVER_METADATA)
        );
        final var tokenEndpoint = post(authorizationServerSettings.getTokenEndpoint());
        final var clientAuthenticatedEndpoints = new OrRequestMatcher(
                tokenEndpoint,
                post(authorizationServerSettings.getTokenIntrospectionEndpoint()),
                post(authorizationServerSettings.getTokenRevocationEndpoint())
        );
        final var mcpResource = appProperties.getMcp().getIssuer() + MCP_RESOURCE_PATH;
        final var connectContinue = path(ConnectContinueController.CONTINUE_PATH);
        final var connectRedirectEntryPoint = new ConnectRedirectEntryPoint(connectRequestStore, registeredClientRepository, appProperties.getFrontendUrl());
        final var authorizationResponseHandler = new SessionEndingAuthorizationResponseHandler();

        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(connectContinue).permitAll()
                        .anyRequest().authenticated())
                .with(McpAuthorizationServerConfigurer.mcpAuthorizationServer(), mcp -> mcp
                        .authorizationCodeRequestValidator(new LocalhostWildcardPortValidator().andThen(DEFAULT_SCOPE_VALIDATOR))
                        .dynamicClientRegistrationValidator(OAuth2ClientRegistrationAuthenticationValidator.DEFAULT_REDIRECT_URI_VALIDATOR
                                .andThen(new DcrRedirectUriValidator())
                                .andThen(OAuth2ClientRegistrationAuthenticationValidator.DEFAULT_JWK_SET_URI_VALIDATOR)
                                .andThen(new DcrScopeValidator())
                                .andThen(new DcrGrantTypeValidator()))
                        .authorizationServer(authorizationServer -> {
                            http.securityMatcher(new OrRequestMatcher(authorizationServer.getEndpointsMatcher(), unsupportedDiscovery, connectContinue));
                            authorizationServer
                                    .authorizationEndpoint(authorizationEndpoint -> authorizationEndpoint
                                            .authorizationResponseHandler(authorizationResponseHandler)
                                            .errorResponseHandler(authorizationResponseHandler))
                                    .tokenGenerator(withPublicClientRefreshTokens(http.getSharedObject(OAuth2TokenGenerator.class)))
                                    .clientAuthentication(clientAuthentication -> clientAuthentication
                                            .authenticationConverters(converters -> converters.add(new PublicClientRefreshTokenAuthenticationConverter(tokenEndpoint)))
                                            .authenticationProvider(new PublicClientRefreshTokenAuthenticationProvider(registeredClientRepository)))
                                    .tokenEndpoint(tokenEndpointConfigurer -> tokenEndpointConfigurer
                                            .authenticationProviders(providers -> providers
                                                    .replaceAll(provider -> new ResourceIndicatorValidatingProvider(provider, mcpResource))))
                                    .authorizationServerMetadataEndpoint(metadata -> metadata
                                            .authorizationServerMetadataCustomizer(builder -> builder
                                                    .tokenEndpointAuthenticationMethods(AuthorizationServerConfiguration::advertisePublicClients)))
                                    .clientRegistrationEndpoint(clientRegistration -> clientRegistration
                                            .authenticationProviders(AuthorizationServerConfiguration::useDcrClientSettings));
                        }))
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .defaultAuthenticationEntryPointFor(connectRedirectEntryPoint, path(AUTHORIZATION_ENDPOINT))
                        .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.NOT_FOUND), unsupportedDiscovery)
                        .defaultAuthenticationEntryPointFor(new InvalidClientEntryPoint(), clientAuthenticatedEndpoints))
                .cors(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder()
                .issuer(appProperties.getMcp().getIssuer())
                .build();
    }

    @Bean
    public RSAKey mcpSigningKey() {
        return parseSigningKey(appProperties.getMcp().getSigningKey());
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(final RSAKey mcpSigningKey) {
        return new ImmutableJWKSet<>(new JWKSet(mcpSigningKey));
    }

    @Bean
    public JwtDecoder mcpJwtDecoder(final RSAKey mcpSigningKey) throws JOSEException {
        final var jwtDecoder = NimbusJwtDecoder.withPublicKey(mcpSigningKey.toRSAPublicKey()).build();
        jwtDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(appProperties.getMcp().getIssuer()));

        return jwtDecoder;
    }

    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> refreshAudienceCustomizer() {
        return new RefreshAudienceCustomizer();
    }

    @Bean
    public RegisteredClientRepository registeredClientRepository(final JdbcOperations jdbcOperations) {
        return new JdbcRegisteredClientRepository(jdbcOperations);
    }

    @Bean
    public OAuth2AuthorizationService authorizationService(final JdbcOperations jdbcOperations,
                                                           final RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationService(jdbcOperations, registeredClientRepository);
    }

    @Bean
    public OAuth2AuthorizationConsentService authorizationConsentService(final JdbcOperations jdbcOperations,
                                                                         final RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcOperations, registeredClientRepository);
    }

    @Bean
    public FilterRegistrationBean<DcrEmptyScopeFilter> dcrEmptyScopeFilterRegistration(final JsonMapper jsonMapper) {
        final var registration = new FilterRegistrationBean<>(new DcrEmptyScopeFilter(jsonMapper));
        registration.addUrlPatterns(CLIENT_REGISTRATION_ENDPOINT);
        registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER - 100);

        return registration;
    }

    static RSAKey parseSigningKey(final String signingKey) {
        final RSAKey rsaKey;
        try {
            rsaKey = RSAKey.parse(signingKey);
        } catch (final ParseException exception) {
            throw new IllegalStateException(SIGNING_KEY_PROPERTY + " is not a valid RSA JWK: " + exception.getMessage());
        }

        if (!rsaKey.isPrivate()) {
            throw new IllegalStateException(SIGNING_KEY_PROPERTY + " must be a private RSA JWK");
        }
        if (rsaKey.size() < MINIMUM_SIGNING_KEY_SIZE) {
            throw new IllegalStateException(SIGNING_KEY_PROPERTY + " must be at least " + MINIMUM_SIGNING_KEY_SIZE + " bits");
        }

        return rsaKey;
    }

    private static OAuth2TokenGenerator<OAuth2Token> withPublicClientRefreshTokens(final OAuth2TokenGenerator<?> mcpTokenGenerator) {
        if (mcpTokenGenerator == null) {
            throw new IllegalStateException("The MCP authorization server did not configure a token generator");
        }

        return new DelegatingOAuth2TokenGenerator(mcpTokenGenerator, new PublicClientRefreshTokenGenerator());
    }

    private static void advertisePublicClients(final List<String> authenticationMethods) {
        final var none = ClientAuthenticationMethod.NONE.getValue();
        if (!authenticationMethods.contains(none)) {
            authenticationMethods.add(none);
        }
    }

    private static void useDcrClientSettings(final List<AuthenticationProvider> authenticationProviders) {
        authenticationProviders.stream()
                .filter(OAuth2ClientRegistrationAuthenticationProvider.class::isInstance)
                .map(OAuth2ClientRegistrationAuthenticationProvider.class::cast)
                .forEach(provider -> provider.setRegisteredClientConverter(new DcrClientSettings()));
    }

    private static RequestMatcher path(final String pattern) {
        return PathPatternRequestMatcher.withDefaults().matcher(pattern);
    }

    private static RequestMatcher post(final String pattern) {
        return PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, pattern);
    }

}
