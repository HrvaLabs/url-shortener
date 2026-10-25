package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.stats.LinksStatsResponse;
import cc.hrva.urlshortener.dto.stats.SafetyStatsResponse;
import cc.hrva.urlshortener.dto.stats.TrafficStatsResponse;
import cc.hrva.urlshortener.dto.stats.UsersStatsResponse;
import cc.hrva.urlshortener.service.AdminStatsService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/admin/stats")
@PreAuthorize("hasRole('ROLE_ADMIN')")
public class AdminStatsController {

    private final AdminStatsService adminStatsService;

    @GetMapping("/traffic")
    public ResponseEntity<TrafficStatsResponse> traffic(@RequestParam(name = "days", required = false) final Integer days) {
        return ResponseEntity.ok(adminStatsService.traffic(days));
    }

    @GetMapping("/links")
    public ResponseEntity<LinksStatsResponse> links(@RequestParam(name = "days", required = false) final Integer days) {
        return ResponseEntity.ok(adminStatsService.links(days));
    }

    @GetMapping("/users")
    public ResponseEntity<UsersStatsResponse> users(@RequestParam(name = "days", required = false) final Integer days) {
        return ResponseEntity.ok(adminStatsService.users(days));
    }

    @GetMapping("/safety")
    public ResponseEntity<SafetyStatsResponse> safety(@RequestParam(name = "days", required = false) final Integer days) {
        return ResponseEntity.ok(adminStatsService.safety(days));
    }

}
