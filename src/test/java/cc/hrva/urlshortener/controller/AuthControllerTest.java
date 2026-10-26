package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.dto.*;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.service.AuthService;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    private HttpServletRequest request;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(authService, userService, twoFactorService, request))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    void shouldRegister() throws Exception {
        when(authService.register(any(UserRegisterDto.class))).thenReturn("test@example.com");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(UserRegisterDto.builder().email("test@example.com").password("password123").build())))
                .andExpect(status().isOk());
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
                .andExpect(jsonPath("$.email").value("Enter a valid email address (e.g. you@example.com)"));

        verifyNoInteractions(authService);
    }

    @Test
    void refusesAValidEmailAddressLongerThanTheAccountCanStoreBeforeAnythingIsSavedOrSent() throws Exception {
        final var email = "a".repeat(64) + "@" + ("b".repeat(63) + ".").repeat(3) + "example.com";

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(UserRegisterDto.builder().email(email).password("password123").build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.email").value("Email must be 255 characters or fewer"));

        verifyNoInteractions(authService);
    }

    @Test
    void acceptsALocalPartOfExactly64Characters() throws Exception {
        final var email = "a".repeat(64) + "@example.com";
        when(authService.register(any(UserRegisterDto.class))).thenReturn(email);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(UserRegisterDto.builder().email(email).password("password123").build())))
                .andExpect(status().isOk());
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
    void shouldResetPassword() throws Exception {
        final var user = User.builder().id(1L).email("test@example.com").build();
        when(userService.resetPassword(any(PasswordResetDto.class))).thenReturn(user);

        mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(PasswordResetDto.builder().email("test@example.com").token("token").password("newPassword").build())))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/json"));
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

        verify(twoFactorService).disable("123456");
    }

    @Test
    void shouldLogin() throws Exception {
        final var jwtDto = JWTTokenDto.builder().token("Bearer token").user(UserDto.builder().id(1L).email("test@example.com").build()).build();
        final var headers = new HttpHeaders();
        headers.add("Authorization", "Bearer token");
        when(authService.login(any(LoginDto.class), any())).thenReturn(new ResponseEntity<>(jwtDto, headers, org.springframework.http.HttpStatus.OK));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(LoginDto.builder().email("test@example.com").password("password123").build())))
                .andExpect(status().isOk())
                .andExpect(header().string("Authorization", "Bearer token"));
    }
}
