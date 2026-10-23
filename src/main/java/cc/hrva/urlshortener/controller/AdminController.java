package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.audit.Audited;
import cc.hrva.urlshortener.dto.AdminStatsResponse;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.service.AdminService;
import cc.hrva.urlshortener.service.LoginAttemptService;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequestMapping("api/v1/admin")
@PreAuthorize("hasRole('ROLE_ADMIN')")
@Tag(name = "Admin", description = "Admin dashboard operations")
public class AdminController {

    private final AdminService adminService;
    private final LoginAttemptService loginAttemptService;

    public AdminController(final AdminService adminService, final LoginAttemptService loginAttemptService) {
        this.adminService = adminService;
        this.loginAttemptService = loginAttemptService;
    }

    @GetMapping("/stats")
    @Operation(summary = "Get admin dashboard stats", description = "Returns system-wide statistics. Requires ROLE_ADMIN.")
    public ResponseEntity<AdminStatsResponse> getDashboardStats() {
        return ResponseEntity.ok(adminService.getDashboardStats());
    }

    @Operation(summary = "Export all URLs as CSV", description = "Download all URLs as a CSV file. Requires ROLE_ADMIN.")
    @GetMapping("/urls/export")
    public ResponseEntity<byte[]> exportAllUrls() {
        final var csv = adminService.exportAllUrlsAsCsv();
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=all-urls.csv")
                .contentType(MediaType.TEXT_PLAIN)
                .body(csv);
    }

    @Operation(summary = "Get login attempts", description = "View current rate-limited IP addresses and their attempt counts. Requires ROLE_ADMIN.")
    @GetMapping("/login-attempts")
    public ResponseEntity<Map<String, Integer>> getLoginAttempts() {
        return ResponseEntity.ok(loginAttemptService.getLoginAttempts());
    }

    @Operation(summary = "Clear login attempts", description = "Clear all rate limit data for all IPs. Requires ROLE_ADMIN.")
    @DeleteMapping("/login-attempts")
    @Audited(AuditAction.CLEAR_LOGIN_ATTEMPTS)
    public ResponseEntity<Void> clearLoginAttempts() {
        loginAttemptService.clearLoginAttempts();
        return ResponseEntity.noContent().build();
    }

}
