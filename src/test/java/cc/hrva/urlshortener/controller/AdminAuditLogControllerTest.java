package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.model.AuditLog;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.service.AuditLogService;
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
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
        when(auditLogService.getAuditLogs(any(Pageable.class), isNull()))
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
        when(auditLogService.getAuditLogs(any(Pageable.class), eq(AuditAction.RESOLVE_REVIEW)))
                .thenReturn(new PageImpl<>(List.of(RESOLVED), PageRequest.of(0, 50), 1));

        mockMvc.perform(get("/api/v1/admin/audit-log").param("action", "RESOLVE_REVIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(41));
    }

    @Test
    void anUnknownActionFilterIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-log").param("action", "resolveReview"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid value for action"));

        verifyNoInteractions(auditLogService);
    }

}
