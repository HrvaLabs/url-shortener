package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.audit.Audited;
import cc.hrva.urlshortener.model.EmailLog;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.model.enums.EmailStatus;
import cc.hrva.urlshortener.service.EmailLogService;
import cc.hrva.urlshortener.service.SendingEmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/admin/email-log")
public class AdminEmailLogController {

    private final EmailLogService emailLogService;
    private final SendingEmailService sendingEmailService;

    @GetMapping
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<Page<EmailLog>> getEmailLogs(
            @PageableDefault(size = 50) final Pageable pageable,
            @RequestParam(name = "status", required = false) final EmailStatus status) {
        return ResponseEntity.ok(emailLogService.emails(status, pageable));
    }

    @PostMapping("/{id}/resend")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Audited(AuditAction.RESEND_EMAIL)
    public ResponseEntity<EmailLog> resendEmail(@PathVariable("id") final Long id) {
        return ResponseEntity.ok(sendingEmailService.resendEmail(id));
    }

}
