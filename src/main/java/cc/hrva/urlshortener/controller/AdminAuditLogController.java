package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.AuditLogFilter;
import cc.hrva.urlshortener.model.AuditLog;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.service.AuditLogService;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/admin/audit-log")
public class AdminAuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<Page<AuditLog>> getAuditLogs(
            @PageableDefault(size = 50) final Pageable pageable,
            @RequestParam(name = "action", required = false) final AuditAction action,
            @RequestParam(name = "search", required = false) final String search,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) final LocalDate to) {
        return ResponseEntity.ok(auditLogService.getAuditLogs(pageable, new AuditLogFilter(action, search, from, to)));
    }

}
