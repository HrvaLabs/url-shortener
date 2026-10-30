package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.model.EmailLog;
import cc.hrva.urlshortener.model.enums.EmailStatus;
import cc.hrva.urlshortener.service.EmailLogService;
import cc.hrva.urlshortener.service.SendingEmailService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminEmailLogControllerTest {

    private static final String EMAIL_LOG = "/api/v1/admin/email-log";
    private static final Pageable FIRST_PAGE = PageRequest.of(0, 50);
    private static final EmailLog SENT = EmailLog.builder()
            .id(87L)
            .recipient("ana@example.com")
            .subject("Verify your email")
            .status(EmailLog.STATUS_SENT)
            .createdAt(LocalDateTime.of(2026, 10, 25, 9, 30))
            .build();
    private static final EmailLog FAILED = EmailLog.builder()
            .id(12L)
            .recipient("ben@example.com")
            .subject("Your link was blocked as unsafe")
            .status(EmailLog.STATUS_FAILED)
            .errorMessage("Connection refused")
            .createdAt(LocalDateTime.of(2026, 10, 20, 8, 0))
            .build();

    @Mock
    private EmailLogService emailLogService;

    @Mock
    private SendingEmailService sendingEmailService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminEmailLogController(emailLogService, sendingEmailService))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void withoutAStatusTheWholeLogIsListedFiftyAPage() throws Exception {
        when(emailLogService.emails(null, FIRST_PAGE)).thenReturn(new PageImpl<>(List.of(SENT, FAILED), FIRST_PAGE, 2));

        mockMvc.perform(get(EMAIL_LOG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(87))
                .andExpect(jsonPath("$.content[1].status").value("FAILED"));

        verify(emailLogService).emails(null, FIRST_PAGE);
    }

    @Test
    void aStatusKeepsOnlyTheEmailsWithIt() throws Exception {
        when(emailLogService.emails(EmailStatus.FAILED, FIRST_PAGE)).thenReturn(new PageImpl<>(List.of(FAILED), FIRST_PAGE, 1));

        mockMvc.perform(get(EMAIL_LOG).param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(12))
                .andExpect(jsonPath("$.content[0].errorMessage").value("Connection refused"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SENDING", "SENT", "FAILED", "RESENT"})
    void everyStatusOfTheLogCanBeKept(final String status) throws Exception {
        when(emailLogService.emails(eq(EmailStatus.valueOf(status)), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(), FIRST_PAGE, 0));

        mockMvc.perform(get(EMAIL_LOG).param("status", status))
                .andExpect(status().isOk());

        verify(emailLogService).emails(eq(EmailStatus.valueOf(status)), any(Pageable.class));
    }

    @Test
    void thePageAndTheSortArePassedOn() throws Exception {
        final var thirdPage = PageRequest.of(2, 10, Sort.by(Sort.Order.asc("createdAt")));
        when(emailLogService.emails(EmailStatus.FAILED, thirdPage)).thenReturn(new PageImpl<>(List.of(FAILED), thirdPage, 21));

        mockMvc.perform(get(EMAIL_LOG).param("status", "FAILED").param("page", "2").param("size", "10").param("sort", "createdAt,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(21));

        verify(emailLogService).emails(EmailStatus.FAILED, thirdPage);
    }

    @Test
    void anEmptyStatusListsTheWholeLog() throws Exception {
        when(emailLogService.emails(null, FIRST_PAGE)).thenReturn(new PageImpl<>(List.of(SENT, FAILED), FIRST_PAGE, 2));

        mockMvc.perform(get(EMAIL_LOG).param("status", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        verify(emailLogService).emails(null, FIRST_PAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"BOUNCED", "2"})
    void anUnknownStatusIsABadRequest(final String status) throws Exception {
        mockMvc.perform(get(EMAIL_LOG).param("status", status))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid value for status"));

        verifyNoInteractions(emailLogService);
    }

}
