package cc.hrva.urlshortener.mcp;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.controller.UrlRedirectController;
import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.tools.LinkTools;
import cc.hrva.urlshortener.mcp.tools.McpUserResolver;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.service.UrlService;
import cc.hrva.urlshortener.service.UserService;
import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.view.RedirectView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = McpEndpointSecurityTest.TestApplication.class,
        properties = {
                "spring.liquibase.change-log=classpath:db/changelog/017-oauth2-authorization-server.xml",
                "spring.security.oauth2.client.registration.google.client-id=test-google-client",
                "spring.security.oauth2.client.registration.google.client-secret=test-google-secret",
                "app.frontend-url=http://localhost:3000",
                "app.jwt-base64-secret=dGVzdC1vbmx5LXNlY3JldA==",
                "app.email-sender-address=no-reply@localhost",
                "app.contact-email=info@localhost",
                "app.server-url=localhost"
        })
@AutoConfigureMockMvc
class McpEndpointSecurityTest {

    private static final String ISSUER = "https://hrva.cc";
    private static final String RESOURCE = ISSUER + "/mcp";
    private static final String RESOURCE_METADATA = ISSUER + "/.well-known/oauth-protected-resource/mcp";
    private static final String EMAIL = "user@localhost";
    private static final String MCP_ACCEPT = "application/json, text/event-stream";
    private static final String TOOLS_LIST = """
            {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""";
    private static final String LIST_LINKS_CALL = """
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"list_links","arguments":{}}}""";
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OAuth2AuthorizationService authorizationService;

    @MockitoBean
    private UrlService urlService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private TokenProvider tokenProvider;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private OAuth2LoginSuccessHandler oAuth2LoginSuccessHandler;

    private McpAccessTokens accessTokens;

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @BeforeEach
    void setUp() {
        accessTokens = new McpAccessTokens(mockMvc);
    }

    @Test
    void mcpWithoutTokenIs401WithResourceMetadata() throws Exception {
        mockMvc.perform(mcpRequest(TOOLS_LIST)
                        .header("X-Forwarded-Host", "evil.example")
                        .header("X-Forwarded-Proto", "https"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("resource_metadata=\"" + RESOURCE_METADATA + "\"")))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, not(containsString("evil.example"))));

        verifyNoInteractions(urlService, userService);
    }

    @Test
    void protectedResourceMetadataNamesResourceAndIssuer() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-protected-resource/mcp")
                        .header("X-Forwarded-Host", "evil.example")
                        .header("X-Forwarded-Proto", "https"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource").value(RESOURCE))
                .andExpect(jsonPath("$.authorization_servers.length()").value(1))
                .andExpect(jsonPath("$.authorization_servers[0]").value(ISSUER))
                .andExpect(jsonPath("$.resource_name").value("hrva.cc"))
                .andExpect(jsonPath("$.bearer_methods_supported", hasItem("header")))
                .andExpect(jsonPath("$.tls_client_certificate_bound_access_tokens").value(false))
                .andExpect(content().string(not(containsString("evil.example"))));
    }

    @Test
    void tokenWithWrongAudienceIs401() throws Exception {
        final var token = accessTokens.issue(EMAIL, "https://evil.example/mcp");

        mockMvc.perform(mcpRequest(TOOLS_LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("error=\"invalid_token\"")));
    }

    @Test
    void tokenForRemovedAuthorizationIs401() throws Exception {
        final var token = accessTokens.issue(EMAIL, RESOURCE);
        mockMvc.perform(mcpRequest(TOOLS_LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        final var authorization = authorizationService.findByToken(token, OAuth2TokenType.ACCESS_TOKEN);
        assertThat(authorization).isNotNull();
        authorizationService.remove(authorization);

        mockMvc.perform(mcpRequest(TOOLS_LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("error=\"invalid_token\"")));
    }

    @Test
    void validTokenListsTools() throws Exception {
        final var token = accessTokens.issue(EMAIL, RESOURCE);

        final var body = mockMvc.perform(mcpRequest(TOOLS_LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools[*].name", hasItem("list_links")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        final List<Map<String, Object>> inputProperties = JsonPath.read(body, "$.result.tools[?(@.name == 'list_links')].inputSchema.properties");
        assertThat(inputProperties).singleElement()
                .satisfies(properties -> assertThat(properties).containsOnlyKeys("search", "tag", "page"));
    }

    @Test
    void listLinksRunsAsTheTokenSubject() throws Exception {
        final var token = accessTokens.issue(EMAIL, RESOURCE);
        final var callerDuringLookup = new AtomicReference<String>();
        when(userService.fetchUserFromEmail(EMAIL)).thenReturn(User.builder().id(7L).email(EMAIL).active(true).build());
        when(urlService.getAllMyUrls(isNull(), any(), any())).thenAnswer(invocation -> {
            callerDuringLookup.set(SecurityContextHolder.getContext().getAuthentication().getName());
            return new PageImpl<>(List.of(link("abc12", "https://example.com/a")));
        });

        mockMvc.perform(mcpRequest(LIST_LINKS_CALL).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.content[0].text", containsString(ISSUER + "/abc12")));

        verify(userService).fetchUserFromEmail(EMAIL);
        assertThat(callerDuringLookup.get()).isEqualTo(EMAIL);
    }

    @Test
    void getAndPostMcpNeverReachRedirectController() throws Exception {
        final var token = accessTokens.issue(EMAIL, RESOURCE);
        when(urlService.redirectResultUrl(eq("abc12"), any())).thenReturn(new RedirectView("https://example.com/a"));

        mockMvc.perform(get("/mcp"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(mcpRequest(TOOLS_LIST))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/mcp").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(mcpRequest(TOOLS_LIST).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        verify(urlService, never()).redirectResultUrl(any(), any());

        mockMvc.perform(get("/abc12"))
                .andExpect(status().isFound());
        verify(urlService).redirectResultUrl(eq("abc12"), any());
    }

    @Test
    void mcpErrorBodyHasNoStackTrace() throws Exception {
        final var token = accessTokens.issue(EMAIL, RESOURCE);

        for (final var invalidBody : List.of("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":", "{\"unexpected\":true}")) {
            mockMvc.perform(mcpRequest(invalidBody).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(containsString("Invalid message format")))
                    .andExpect(content().string(not(containsString("stackTrace"))))
                    .andExpect(content().string(not(containsString("at cc."))))
                    .andExpect(content().string(not(containsString("\"cause\""))));
        }
    }

    private static MockHttpServletRequestBuilder mcpRequest(final String body) {
        return post("/mcp")
                .header(HttpHeaders.ACCEPT, MCP_ACCEPT)
                .contentType(APPLICATION_JSON)
                .content(body);
    }

    private static UrlResponse link(final String alias, final String destination) {
        return new UrlResponse(1L, destination, alias, LocalDateTime.now(), null, 3L, null, true, "ACTIVE", Set.of(), false, EMAIL);
    }

    private static RSAKey generateSigningKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-signing-key").generate();
        } catch (final JOSEException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            AppConfiguration.class,
            WebConfiguration.class,
            JacksonConfiguration.class,
            SecurityConfiguration.class,
            GlobalExceptionHandler.class,
            AuthorizationServerConfiguration.class,
            McpResourceServerConfiguration.class,
            JwtFilter.class,
            ClientIpResolver.class,
            UrlRedirectController.class,
            McpUserResolver.class,
            LinkTools.class
    })
    static class TestApplication {
    }

}
