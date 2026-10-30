package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.dto.LoginAttemptResponse;
import cc.hrva.urlshortener.exception.InvalidSortException;
import cc.hrva.urlshortener.service.AdminService;
import cc.hrva.urlshortener.service.LoginAttemptService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminLoginAttemptsControllerTest {

    private static final String LOGIN_ATTEMPTS = "/api/v1/admin/login-attempts";

    @Mock
    private AdminService adminService;

    @Mock
    private LoginAttemptService loginAttemptService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminController(adminService, loginAttemptService))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void theAttemptsComeAsAPageOfKeysAndCounts() throws Exception {
        final var firstPage = PageRequest.of(0, 20);
        when(loginAttemptService.attempts(null, firstPage))
                .thenReturn(new PageImpl<>(List.of(new LoginAttemptResponse("203.0.113.7", 12)), firstPage, 1));

        mockMvc.perform(get(LOGIN_ATTEMPTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].key").value("203.0.113.7"))
                .andExpect(jsonPath("$.content[0].attempts").value(12));
    }

    @Test
    void theSearchThePageAndTheSortArePassedOn() throws Exception {
        final var pageable = PageRequest.of(1, 10, Sort.by(Sort.Order.asc("key")));
        when(loginAttemptService.attempts("2fa:", pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 10));

        mockMvc.perform(get(LOGIN_ATTEMPTS).param("search", "2fa:").param("page", "1").param("size", "10").param("sort", "key,asc"))
                .andExpect(status().isOk());

        verify(loginAttemptService).attempts("2fa:", pageable);
    }

    @Test
    void anUnknownSortFieldIsABadRequest() throws Exception {
        when(loginAttemptService.attempts(any(), any(Pageable.class))).thenThrow(new InvalidSortException("Sort must be attempts or key"));

        mockMvc.perform(get(LOGIN_ATTEMPTS).param("sort", "blocked"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Sort must be attempts or key"));
    }

}
