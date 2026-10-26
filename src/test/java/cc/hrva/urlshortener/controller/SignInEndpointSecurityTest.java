package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.AppConfiguration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.JacksonConfiguration;
import cc.hrva.urlshortener.configuration.SecurityConfiguration;
import cc.hrva.urlshortener.configuration.WebConfiguration;
import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.dto.PasskeyDeleteDto;
import cc.hrva.urlshortener.dto.PasskeyOptionsResponse;
import cc.hrva.urlshortener.dto.PasskeyRegistrationOptionsDto;
import cc.hrva.urlshortener.dto.PasskeyRenameDto;
import cc.hrva.urlshortener.dto.PasskeyResponse;
import cc.hrva.urlshortener.dto.TotpSecondStepDto;
import cc.hrva.urlshortener.exception.PasskeyVerificationException;
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
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.PasskeyKind;
import cc.hrva.urlshortener.model.enums.TwoStepMethod;
import cc.hrva.urlshortener.service.AuthService;
import cc.hrva.urlshortener.service.PasskeyService;
import cc.hrva.urlshortener.service.SecondStepService;
import cc.hrva.urlshortener.service.TwoFactorService;
import cc.hrva.urlshortener.service.UserService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
    private static final String PASSKEYS = "/api/v1/users/me/passkeys";
    private static final String SIGNED_IN_TOKEN = "signed-in-token";
    private static final String BEARER = "Bearer " + SIGNED_IN_TOKEN;
    private static final String CEREMONY_ID = "Cr3m0nY1dCr3m0nY1dCr3m0nY1dCr3m0nY1dCr3m0n";
    private static final String REGISTRATION_BODY = """
            {"ceremonyId":"%s","credential":{"id":"a2V5","rawId":"a2V5","type":"public-key","response":{"clientDataJSON":"e30","attestationObject":"oA"}},"label":"MacBook"}"""
            .formatted(CEREMONY_ID);
    private static final PasskeyResponse MACBOOK = new PasskeyResponse(7L, "MacBook", PasskeyKind.SYNCED, true, true, true,
            List.of("hybrid", "internal"), LocalDateTime.of(2026, 10, 25, 14, 5), null);
    private static final String MACBOOK_JSON = """
            {"id":7,"label":"MacBook","kind":"SYNCED","backupEligible":true,"backedUp":true,"discoverable":true,"transports":["hybrid","internal"],"createdAt":"2026-10-25T14:05:00","lastUsedAt":null}""";

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
    private PasskeyService passkeyService;

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
                .andExpect(content().json("{\"error\":\"Your sign-in expired. Sign in again.\"}", JsonCompareMode.STRICT));
    }

    @Test
    void aWrongCodeIsA401WithItsText() throws Exception {
        when(secondStepService.completeWithCode(any(), any())).thenThrow(new TwoStepFailedException("Invalid two-factor code"));

        mockMvc.perform(post(TOTP_SECOND_STEP).contentType(MediaType.APPLICATION_JSON).content(TOTP_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"Invalid two-factor code\"}", JsonCompareMode.STRICT));
    }

    @Test
    void anUnavailableSignInStoreIsA503WithItsText() throws Exception {
        when(secondStepService.completeWithCode(any(), any())).thenThrow(new SignInUnavailableException(
                "Two-step sign-in isn't available right now. Try again in a minute.", new QueryTimeoutException("down")));

        mockMvc.perform(post(TOTP_SECOND_STEP).contentType(MediaType.APPLICATION_JSON).content(TOTP_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"error\":\"Two-step sign-in isn't available right now. Try again in a minute.\"}", JsonCompareMode.STRICT));
    }

    @Test
    void aSecondStepWithoutACodeIsRefusedBeforeTheServiceRuns() throws Exception {
        mockMvc.perform(post(TOTP_SECOND_STEP).contentType(MediaType.APPLICATION_JSON).content("{\"pendingToken\":\"" + PENDING_TOKEN + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").exists());

        verifyNoInteractions(secondStepService);
    }

    @Test
    void passkeyManagementNeedsSignIn() throws Exception {
        mockMvc.perform(get(PASSKEYS))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(PASSKEYS + "/registration/options").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"user123\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(PASSKEYS).contentType(MediaType.APPLICATION_JSON).content(REGISTRATION_BODY))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch(PASSKEYS + "/7").contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"Work YubiKey\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(PASSKEYS + "/7"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(passkeyService);
    }

    @Test
    void aSignedInUserCanListPasskeys() throws Exception {
        signedIn();
        when(passkeyService.list()).thenReturn(List.of(MACBOOK));

        mockMvc.perform(get(PASSKEYS).header(JwtFilter.AUTHORIZATION_HEADER, BEARER))
                .andExpect(status().isOk())
                .andExpect(content().json("[" + MACBOOK_JSON + "]", JsonCompareMode.STRICT));
    }

    @Test
    void aVerificationFailureIsNeverA500OrLibraryText() throws Exception {
        signedIn();
        when(passkeyService.register(any()))
                .thenThrow(new PasskeyVerificationException("That passkey or security key couldn't be verified. Try again."));

        mockMvc.perform(post(PASSKEYS).header(JwtFilter.AUTHORIZATION_HEADER, BEARER)
                        .contentType(MediaType.APPLICATION_JSON).content(REGISTRATION_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"error\":\"That passkey or security key couldn't be verified. Try again.\"}", JsonCompareMode.STRICT));
    }

    @Test
    void addingIs201RenamingIs200AndRemovingIs204() throws Exception {
        signedIn();
        when(passkeyService.register(any())).thenReturn(MACBOOK);
        when(passkeyService.rename(7L, new PasskeyRenameDto("Work YubiKey"))).thenReturn(MACBOOK);

        mockMvc.perform(post(PASSKEYS).header(JwtFilter.AUTHORIZATION_HEADER, BEARER)
                        .contentType(MediaType.APPLICATION_JSON).content(REGISTRATION_BODY))
                .andExpect(status().isCreated())
                .andExpect(content().json(MACBOOK_JSON, JsonCompareMode.STRICT));
        mockMvc.perform(patch(PASSKEYS + "/7").header(JwtFilter.AUTHORIZATION_HEADER, BEARER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"Work YubiKey\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete(PASSKEYS + "/7").header(JwtFilter.AUTHORIZATION_HEADER, BEARER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"user123\"}"))
                .andExpect(status().isNoContent());

        verify(passkeyService).delete(7L, new PasskeyDeleteDto("user123"));
    }

    @Test
    void optionsAndRemovalTakeAnOptionalBody() throws Exception {
        signedIn();
        when(passkeyService.registrationOptions(any()))
                .thenReturn(new PasskeyOptionsResponse(CEREMONY_ID, JsonMapper.builder().build().readTree("{\"challenge\":\"Y2hhbGxlbmdl\"}")));

        mockMvc.perform(post(PASSKEYS + "/registration/options").header(JwtFilter.AUTHORIZATION_HEADER, BEARER))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"ceremonyId\":\"" + CEREMONY_ID + "\",\"options\":{\"challenge\":\"Y2hhbGxlbmdl\"}}", JsonCompareMode.STRICT));
        mockMvc.perform(delete(PASSKEYS + "/7").header(JwtFilter.AUTHORIZATION_HEADER, BEARER))
                .andExpect(status().isNoContent());

        verify(passkeyService).registrationOptions(new PasskeyRegistrationOptionsDto());
        verify(passkeyService).delete(7L, new PasskeyDeleteDto());
    }

    @Test
    void aLongLabelIsRefusedBeforeTheServiceRuns() throws Exception {
        signedIn();

        mockMvc.perform(post(PASSKEYS).header(JwtFilter.AUTHORIZATION_HEADER, BEARER)
                        .contentType(MediaType.APPLICATION_JSON).content(REGISTRATION_BODY.replace("MacBook", "x".repeat(65))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.label").exists());
        mockMvc.perform(patch(PASSKEYS + "/7").header(JwtFilter.AUTHORIZATION_HEADER, BEARER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.label").exists());

        verify(passkeyService, never()).register(any());
        verify(passkeyService, never()).rename(any(), any());
    }

    private void signedIn() {
        final var authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        when(tokenProvider.validateToken(SIGNED_IN_TOKEN)).thenReturn(true);
        when(tokenProvider.getAuthentication(SIGNED_IN_TOKEN))
                .thenReturn(new UsernamePasswordAuthenticationToken("user@localhost", SIGNED_IN_TOKEN, authorities));
        when(userRepository.findByEmail("user@localhost"))
                .thenReturn(Optional.of(User.builder().email("user@localhost").active(true).build()));
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
            TwoStepController.class,
            PasskeyController.class
    })
    static class TestApplication {
    }

}
