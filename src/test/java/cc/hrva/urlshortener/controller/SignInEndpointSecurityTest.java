package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.dto.TotpSecondStepDto;
import cc.hrva.urlshortener.exception.SignInExpiredException;
import cc.hrva.urlshortener.exception.SignInUnavailableException;
import cc.hrva.urlshortener.exception.TwoStepFailedException;
import cc.hrva.urlshortener.mcp.McpResourceServerConfiguration;
import cc.hrva.urlshortener.mcp.anon.RateLimiter;
import cc.hrva.urlshortener.mcp.authorization.AuthorizationServerConfiguration;
import cc.hrva.urlshortener.mcp.bridge.ConnectRequestStore;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.security.JwtFilter;
import cc.hrva.urlshortener.security.OAuth2LoginSuccessHandler;
import cc.hrva.urlshortener.security.TokenProvider;
import cc.hrva.urlshortener.model.enums.TwoStepMethod;
import cc.hrva.urlshortener.service.AuthService;
import cc.hrva.urlshortener.service.SecondStepService;
import cc.hrva.urlshortener.service.TwoFactorService;
import cc.hrva.urlshortener.service.UserService;
import java.util.List;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = SignInEndpointSecurityTest.TestApplication.class,
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
class SignInEndpointSecurityTest {

    private static final String LOGIN = "/api/v1/auth/login";
    private static final String TOTP_SECOND_STEP = "/api/v1/auth/2fa/totp";
    private static final String PENDING_TOKEN = "Pn4m1qL0rT8vWx2yZ3aB4cD5eF6gH7iJ8kL9mN0oP1q";
    private static final String TOTP_BODY = """
            {"pendingToken":"%s","code":"123456"}""".formatted(PENDING_TOKEN);
    private static final RSAKey SIGNING_KEY = generateSigningKey();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SecondStepService secondStepService;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private TwoFactorService twoFactorService;

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

    @DynamicPropertySource
    static void signingKey(final DynamicPropertyRegistry registry) {
        registry.add("app.mcp.signing-key", SIGNING_KEY::toJSONString);
    }

    @Test
    void aTwoStepLoginAnswersWithThePendingTokenAndTheOldFlag() throws Exception {
        when(authService.login(any(), any())).thenReturn(ResponseEntity.ok(
                JWTTokenDto.twoStepRequired(PENDING_TOKEN, List.of(TwoStepMethod.TOTP, TwoStepMethod.RECOVERY_CODE))));

        mockMvc.perform(post(LOGIN).contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"user@example.com","password":"password123"}"""))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(JwtFilter.AUTHORIZATION_HEADER))
                .andExpect(content().string("""
                        {"token":null,"user":null,"twoFactorRequired":true,"twoStepRequired":true,"pendingToken":"%s","methods":["TOTP","RECOVERY_CODE"]}"""
                        .formatted(PENDING_TOKEN)));
    }

    @Test
    void theTotpSecondStepIsPublic() throws Exception {
        when(secondStepService.completeWithCode(any(), any()))
                .thenReturn(ResponseEntity.ok(JWTTokenDto.builder().token("Bearer eyJ.token").build()));

        mockMvc.perform(post(TOTP_SECOND_STEP).contentType(MediaType.APPLICATION_JSON).content(TOTP_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("Bearer eyJ.token"));

        verify(secondStepService).completeWithCode(eq(TotpSecondStepDto.builder().pendingToken(PENDING_TOKEN).code("123456").build()), any());
        verifyNoInteractions(tokenProvider);
    }

    @Test
    void anExpiredPendingTokenIsA410WithItsText() throws Exception {
        when(secondStepService.completeWithCode(any(), any())).thenThrow(new SignInExpiredException());

        mockMvc.perform(post(TOTP_SECOND_STEP).contentType(MediaType.APPLICATION_JSON).content(TOTP_BODY))
                .andExpect(status().isGone())
                .andExpect(content().json("{\"error\":\"Your sign-in expired. Sign in again.\"}", true));
    }

    @Test
    void aWrongCodeIsA401WithItsText() throws Exception {
        when(secondStepService.completeWithCode(any(), any())).thenThrow(new TwoStepFailedException("Invalid two-factor code"));

        mockMvc.perform(post(TOTP_SECOND_STEP).contentType(MediaType.APPLICATION_JSON).content(TOTP_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"Invalid two-factor code\"}", true));
    }

    @Test
    void anUnavailableSignInStoreIsA503WithItsText() throws Exception {
        when(secondStepService.completeWithCode(any(), any())).thenThrow(new SignInUnavailableException(
                "Two-step sign-in isn't available right now. Try again in a minute.", new QueryTimeoutException("down")));

        mockMvc.perform(post(TOTP_SECOND_STEP).contentType(MediaType.APPLICATION_JSON).content(TOTP_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"error\":\"Two-step sign-in isn't available right now. Try again in a minute.\"}", true));
    }

    @Test
    void aSecondStepWithoutACodeIsRefusedBeforeTheServiceRuns() throws Exception {
        mockMvc.perform(post(TOTP_SECOND_STEP).contentType(MediaType.APPLICATION_JSON).content("{\"pendingToken\":\"" + PENDING_TOKEN + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").exists());

        verifyNoInteractions(secondStepService);
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
            AuthController.class,
            TwoStepController.class
    })
    static class TestApplication {
    }

}
