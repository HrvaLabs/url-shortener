package cc.hrva.urlshortener.mcp;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.mcp.admin.AdminMcpAuthorizationManager;
import cc.hrva.urlshortener.mcp.admin.AdminMcpServerConfiguration;
import cc.hrva.urlshortener.mcp.authorization.StillAuthorizedValidator;
import cc.hrva.urlshortener.repository.UserRepository;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import org.springaicommunity.mcp.security.server.config.McpServerOAuth2Configurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration
@RequiredArgsConstructor
public class McpResourceServerConfiguration {

    static final String[] REMOVED_ANONYMOUS_MCP_PATHS = {"/mcp/anon", "/mcp/anon/**"};

    private static final String MCP_ENDPOINT = "/mcp";
    private static final String PROTECTED_RESOURCE_METADATA = "/.well-known/oauth-protected-resource";
    private static final String RESOURCE_NAME = "hrva.cc";
    private static final String ADMIN_RESOURCE_NAME = "hrva.cc admin";

    private final AppProperties appProperties;

    @Bean
    @Order(2)
    public SecurityFilterChain adminMcpResourceServerSecurityFilterChain(final HttpSecurity http,
                                                                         final JwtDecoder mcpJwtDecoder,
                                                                         final OAuth2AuthorizationService authorizationService,
                                                                         final UserRepository userRepository) throws Exception {
        http.securityMatcher(AdminMcpServerConfiguration.ENDPOINT, PROTECTED_RESOURCE_METADATA + AdminMcpServerConfiguration.ENDPOINT)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().access(new AdminMcpAuthorizationManager(userRepository)));

        return protectedResource(http, AdminMcpServerConfiguration.ENDPOINT, ADMIN_RESOURCE_NAME, mcpJwtDecoder, authorizationService).build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain mcpResourceServerSecurityFilterChain(final HttpSecurity http,
                                                                    final JwtDecoder mcpJwtDecoder,
                                                                    final OAuth2AuthorizationService authorizationService) throws Exception {
        http.securityMatcher(MCP_ENDPOINT, PROTECTED_RESOURCE_METADATA + "/**")
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated());

        return protectedResource(http, MCP_ENDPOINT, RESOURCE_NAME, mcpJwtDecoder, authorizationService).build();
    }

    @Bean
    @Order(4)
    public SecurityFilterChain removedAnonymousMcpSecurityFilterChain(final HttpSecurity http) throws Exception {
        http.securityMatcher(REMOVED_ANONYMOUS_MCP_PATHS)
                .authorizeHttpRequests(authorize -> authorize.anyRequest().denyAll())
                .exceptionHandling(exceptionHandling -> exceptionHandling
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.NOT_FOUND)))
                .sessionManagement(sessionManagement -> sessionManagement.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable);

        return http.build();
    }

    private HttpSecurity protectedResource(final HttpSecurity http,
                                           final String endpoint,
                                           final String resourceName,
                                           final JwtDecoder mcpJwtDecoder,
                                           final OAuth2AuthorizationService authorizationService) throws Exception {
        final var issuer = appProperties.getMcp().getIssuer();
        final var resource = issuer + endpoint;
        final var accessTokenValidator = new DelegatingOAuth2TokenValidator<>(
                new JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD, audience -> audience != null && audience.contains(resource)),
                new StillAuthorizedValidator(authorizationService)
        );

        return http.with(McpServerOAuth2Configurer.mcpServerOAuth2(), mcp -> mcp
                        .authorizationServer(issuer)
                        .jwtDecoder(validating(mcpJwtDecoder, accessTokenValidator))
                        .protectedResourceMetadataCustomizer(metadata -> metadata
                                .resource(resource)
                                .authorizationServer(issuer)
                                .resourceName(resourceName)
                                .tlsClientCertificateBoundAccessTokens(false))
                        .oauth2ResourceServer(resourceServer -> resourceServer
                                .authenticationEntryPoint(resourceMetadataEntryPoint(issuer + PROTECTED_RESOURCE_METADATA + endpoint))))
                .sessionManagement(sessionManagement -> sessionManagement.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults());
    }

    private static JwtDecoder validating(final JwtDecoder jwtDecoder, final OAuth2TokenValidator<Jwt> validator) {
        return token -> {
            final var jwt = jwtDecoder.decode(token);
            final var result = validator.validate(jwt);

            if (result.hasErrors()) {
                throw new JwtValidationException(result.getErrors().iterator().next().getDescription(), result.getErrors());
            }

            return jwt;
        };
    }

    private static AuthenticationEntryPoint resourceMetadataEntryPoint(final String resourceMetadata) {
        final var entryPoint = new BearerTokenAuthenticationEntryPoint();
        entryPoint.setResourceMetadataParameterResolver(request -> resourceMetadata);

        return entryPoint;
    }

}
