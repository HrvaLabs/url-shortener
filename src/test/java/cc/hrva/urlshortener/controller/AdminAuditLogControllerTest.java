package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.dto.AuditLogFilter;
import cc.hrva.urlshortener.model.AuditLog;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.service.AuditLogService;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminAuditLogControllerTest {

    private static final AuditLog RESOLVED = AuditLog.builder()
            .id(41L)
            .performedBy("admin@hrva.cc")
            .action(AuditAction.RESOLVE_REVIEW)
            .targetType("Url")
            .targetIdentifier("287")
            .details("action: BLOCK_DOMAIN, domain: findfavoritedate.life, links: 2, via: admin")
            .performedAt(LocalDateTime.of(2026, 10, 23, 9, 30))
            .build();
    private static final AuditLog FAILED_UPDATE = AuditLog.builder()
            .id(40L)
            .performedBy("admin@hrva.cc")
            .action(AuditAction.UPDATE_URL)
            .failed(true)
            .targetType("Url")
            .targetIdentifier("9")
            .details("Url with id 9 doesn't exist")
            .performedAt(LocalDateTime.of(2026, 10, 23, 9, 0))
            .build();

    @Mock
    private AuditLogService auditLogService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminAuditLogController(auditLogService))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void entriesCarryTheActionNameAndAFailedFlag() throws Exception {
        when(auditLogService.getAuditLogs(any(Pageable.class), eq(AuditLogFilter.NONE)))
                .thenReturn(new PageImpl<>(List.of(RESOLVED, FAILED_UPDATE), PageRequest.of(0, 50), 2));

        mockMvc.perform(get("/api/v1/admin/audit-log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("RESOLVE_REVIEW"))
                .andExpect(jsonPath("$.content[0].failed").value(false))
                .andExpect(jsonPath("$.content[1].action").value("UPDATE_URL"))
                .andExpect(jsonPath("$.content[1].failed").value(true))
                .andExpect(jsonPath("$.content[1].details").value("Url with id 9 doesn't exist"));
    }

    @Test
    void theLogFiltersByAction() throws Exception {
        when(auditLogService.getAuditLogs(any(Pageable.class), eq(new AuditLogFilter(AuditAction.RESOLVE_REVIEW, null, null, null))))
                .thenReturn(new PageImpl<>(List.of(RESOLVED), PageRequest.of(0, 50), 1));

        mockMvc.perform(get("/api/v1/admin/audit-log").param("action", "RESOLVE_REVIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(41));
    }

    @Test
    void theSearchTheDatesThePageAndTheSortArePassedOn() throws Exception {
        final var pageable = PageRequest.of(1, 20, Sort.by(Sort.Order.asc("performedAt")));
        final var filter = new AuditLogFilter(AuditAction.RESOLVE_REVIEW, "findfavoritedate", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 23));
        when(auditLogService.getAuditLogs(pageable, filter)).thenReturn(new PageImpl<>(List.of(RESOLVED), pageable, 21));

        mockMvc.perform(get("/api/v1/admin/audit-log").param("action", "RESOLVE_REVIEW").param("search", "findfavoritedate")
                        .param("from", "2026-10-01").param("to", "2026-10-23").param("page", "1").param("size", "20").param("sort", "performedAt,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(41));

        verify(auditLogService).getAuditLogs(pageable, filter);
    }

    @Test
    void aDateThatIsNotADateIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-log").param("from", "23.10.2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid value for from"));

        verifyNoInteractions(auditLogService);
    }

    @Test
    void anUnknownActionFilterIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-log").param("action", "resolveReview"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid value for action"));

        verifyNoInteractions(auditLogService);
    }

}
