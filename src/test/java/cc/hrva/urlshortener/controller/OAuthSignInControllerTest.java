package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.dto.JWTTokenDto;
import cc.hrva.urlshortener.exception.SignInExpiredException;
import cc.hrva.urlshortener.service.OAuthSignInService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OAuthSignInControllerTest {

    private static final String EXCHANGE = "/api/v1/auth/oauth/exchange";
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

    @Mock
    private OAuthSignInService oAuthSignInService;

    @Mock
    private HttpServletRequest request;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new OAuthSignInController(oAuthSignInService, request))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void theCodeIsExchangedWithTheVerifier() throws Exception {
        when(oAuthSignInService.exchange(eq("one-time-code"), eq(VERIFIER), any()))
                .thenReturn(ResponseEntity.ok(JWTTokenDto.builder().token("Bearer jwt").build()));

        mockMvc.perform(post(EXCHANGE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"one-time-code\",\"verifier\":\"" + VERIFIER + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("Bearer jwt"));
    }

    @Test
    void aCodeWithoutAVerifierIsABadRequestAndIsNotSpent() throws Exception {
        mockMvc.perform(post(EXCHANGE).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"one-time-code\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.verifier").value("Verifier is required"));
        mockMvc.perform(post(EXCHANGE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"one-time-code\",\"verifier\":\"" + "v".repeat(129) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.verifier").value("Verifier must be 128 characters or fewer"));

        verifyNoInteractions(oAuthSignInService);
    }

    @Test
    void aVerifierThatDoesntMatchIsAnsweredLikeAnExpiredCode() throws Exception {
        when(oAuthSignInService.exchange(eq("one-time-code"), eq("someone-elses-verifier"), any())).thenThrow(new SignInExpiredException());

        mockMvc.perform(post(EXCHANGE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"one-time-code\",\"verifier\":\"someone-elses-verifier\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.detail").value("Your sign-in expired. Sign in again."));
    }

}
