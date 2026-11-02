package cc.hrva.urlshortener.controller;

import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import cc.hrva.urlshortener.dto.RegistrationResponse;
import org.springframework.test.json.JsonCompareMode;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.exception.ServiceBusyException;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.ratelimit.AccountEmailLimits;
import java.time.Duration;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.dto.*;
import cc.hrva.urlshortener.service.AuthService;
import cc.hrva.urlshortener.service.TwoStepSettingsService;
import cc.hrva.urlshortener.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    private MockMvc mockMvc;

    @Mock
    private AuthService authService;

    @Mock
    private UserService userService;

    @Mock
    private cc.hrva.urlshortener.service.TwoFactorService twoFactorService;

    @Mock
    private TwoStepSettingsService twoStepSettingsService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private ClientIpResolver clientIpResolver;

    @Mock
    private AccountEmailLimits accountEmailLimits;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService, userService, twoFactorService, twoStepSettingsService, request, clientIpResolver, accountEmailLimits))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    void aSignUpAnswers202WithJson() throws Exception {
        when(authService.register(any(UserRegisterDto.class)))
                .thenReturn(new RegistrationResponse("test@example.com", "Check your inbox for an email from hrva.cc."));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(UserRegisterDto.builder().email("test@example.com").password("password123").build())))
                .andExpect(status().isAccepted())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"email":"test@example.com","message":"Check your inbox for an email from hrva.cc."}""", JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa@example.com",
            "not-an-email",
            "two@@example.com"
    })
    void refusesAnEmailAddressTheAccountCouldNotStoreBeforeAnythingIsSavedOrSent(final String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(UserRegisterDto.builder().email(email).password("password123").build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").value("Enter a valid email address (e.g. you@example.com)"));

        verifyNoInteractions(authService);
    }

    @Test
    void refusesAValidEmailAddressLongerThanTheAccountCanStoreBeforeAnythingIsSavedOrSent() throws Exception {
        final var email = "a".repeat(64) + "@" + ("b".repeat(63) + ".").repeat(3) + "example.com";

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(UserRegisterDto.builder().email(email).password("password123").build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").value("Email must be 255 characters or fewer"));

        verifyNoInteractions(authService);
    }

    @Test
    void acceptsALocalPartOfExactly64Characters() throws Exception {
        final var email = "a".repeat(64) + "@example.com";
        when(authService.register(any(UserRegisterDto.class))).thenReturn(new RegistrationResponse(email, "Check your inbox for an email from hrva.cc."));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(UserRegisterDto.builder().email(email).password("password123").build())))
                .andExpect(status().isAccepted());
    }

    @Test
    void theLoserOfTwoSignUpsForTheSameNewAddressGetsTheSameAnswerAsEveryoneElse() throws Exception {
        final var answer = new RegistrationResponse("race@example.com", "Check your inbox for an email from hrva.cc.");
        when(authService.register(any(UserRegisterDto.class))).thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));
        when(authService.registeredMeanwhile("race@example.com")).thenReturn(Optional.of(answer));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(UserRegisterDto.builder().email("race@example.com").password("password123").build())))
                .andExpect(status().isAccepted())
                .andExpect(content().json("""
                        {"email":"race@example.com","message":"Check your inbox for an email from hrva.cc."}""", JsonCompareMode.STRICT));
    }

    @Test
    void anyOtherIntegrityFailureOnSignUpIsNotHidden() throws Exception {
        when(authService.register(any(UserRegisterDto.class))).thenThrow(new DataIntegrityViolationException("something else"));
        when(authService.registeredMeanwhile("other@example.com")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(UserRegisterDto.builder().email("other@example.com").password("password123").build())))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Internal server error"));
    }

    @Test
    void shouldRequestPasswordReset() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(RequestPasswordResetDto.builder().email("test@example.com").build())))
                .andExpect(status().isAccepted());

        verify(userService).sendPasswordResetLinkToUser(any(RequestPasswordResetDto.class));
    }

    @Test
    void aNetworkOverItsEmailLimitIsToldWhenToTryAgainAndNothingIsLookedUp() throws Exception {
        when(clientIpResolver.getClientIp(request)).thenReturn("203.0.113.7");
        doThrow(new RateLimitExceededException("Too many emails asked for from this network. Try again in 42 minutes.", Duration.ofSeconds(2_520)))
                .when(accountEmailLimits).checkNetwork("203.0.113.7");

        mockMvc.perform(post("/api/v1/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "2520"))
                .andExpect(jsonPath("$.detail").value("Too many emails asked for from this network. Try again in 42 minutes."));
        mockMvc.perform(post("/api/v1/auth/verify-email/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "2520"));

        verifyNoInteractions(userService, authService);
    }

    @Test
    void whileTheEmailLimitCantCountEveryAddressGetsTheSame503AndNothingIsLookedUp() throws Exception {
        when(clientIpResolver.getClientIp(request)).thenReturn("203.0.113.7");
        doThrow(new ServiceBusyException("Account emails are paused for a moment. Try again in a minute.", Duration.ofMinutes(1)))
                .when(accountEmailLimits).checkNetwork("203.0.113.7");

        for (final var path : List.of("/api/v1/auth/password-reset", "/api/v1/auth/verify-email/resend")) {
            for (final var email : List.of("test@example.com", "nobody@example.com")) {
                mockMvc.perform(post(path)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"" + email + "\"}"))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(header().string("Retry-After", "60"))
                        .andExpect(jsonPath("$.detail").value("Account emails are paused for a moment. Try again in a minute."));
            }
        }

        verifyNoInteractions(userService, authService);
    }

    @Test
    void aPasswordResetTakesTheTokenAloneAndAnswers204WithNoBody() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"token\",\"password\":\"newPassword\"}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(userService).resetPassword(PasswordResetDto.builder().token("token").password("newPassword").build());
    }

    @Test
    void anEmailSentByAnOlderPageIsIgnored() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"token\",\"email\":\"test@example.com\",\"password\":\"newPassword\"}"))
                .andExpect(status().isNoContent());

        verify(userService).resetPassword(PasswordResetDto.builder().token("token").password("newPassword").build());
    }

    @Test
    void shouldVerifyEmail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/verify-email/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(VerifyEmailDto.builder().token("tok").build())))
                .andExpect(status().isNoContent());

        verify(authService).verifyEmail("tok");
    }

    @Test
    void shouldResendVerification() throws Exception {
        mockMvc.perform(post("/api/v1/auth/verify-email/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ResendVerificationDto.builder().email("test@example.com").build())))
                .andExpect(status().isAccepted());

        verify(accountEmailLimits).checkNetwork(any());
        verify(authService).resendVerificationEmail("test@example.com");
    }

    @Test
    void shouldSetupTwoFactor() throws Exception {
        when(twoFactorService.setup()).thenReturn(new cc.hrva.urlshortener.dto.TwoFactorSetupResponse("SECRET", "otpauth://totp/x"));

        mockMvc.perform(post("/api/v1/auth/2fa/setup"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").value("SECRET"));
    }

    @Test
    void shouldEnableTwoFactor() throws Exception {
        when(twoFactorService.enable("123456")).thenReturn(new cc.hrva.urlshortener.dto.TwoFactorEnableResponse(java.util.List.of("AAAA-BBBB")));

        mockMvc.perform(post("/api/v1/auth/2fa/enable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(TwoFactorCodeDto.builder().code("123456").build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryCodes[0]").value("AAAA-BBBB"));
    }

    @Test
    void shouldDisableTwoFactor() throws Exception {
        mockMvc.perform(post("/api/v1/auth/2fa/disable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(TwoFactorCodeDto.builder().code("123456").build())))
                .andExpect(status().isNoContent());

        verify(twoStepSettingsService).disable(TwoStepDisableDto.builder().code("123456").build());
        verifyNoInteractions(twoFactorService);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void shouldLogin(final CapturedOutput output) throws Exception {
        final var jwtDto = JWTTokenDto.builder().token("Bearer token").user(UserDto.builder().id(1L).email("test@example.com").build()).build();
        final var headers = new HttpHeaders();
        headers.add("Authorization", "Bearer token");
        when(authService.login(any(LoginDto.class), any())).thenReturn(new ResponseEntity<>(jwtDto, headers, org.springframework.http.HttpStatus.OK));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(LoginDto.builder().email("test@example.com").password("password123").build())))
                .andExpect(status().isOk())
                .andExpect(header().string("Authorization", "Bearer token"));
        assertThat(output).contains("Login controller invoked for user t***@example.com").doesNotContain("test@example.com");
    }
}
