package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.audit.Audited;
import cc.hrva.urlshortener.dto.inspection.LinkInspectionResponse;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.service.inspection.LinkInspectionService;
import cc.hrva.urlshortener.util.ProblemResponses;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/admin/urls/{urlId}/inspection")
public class AdminLinkInspectionController {

    private static final String SCREENSHOT_CACHE = "private, max-age=300";
    private static final String NO_SCREENSHOT = "No screenshot yet.";

    private final LinkInspectionService linkInspectionService;

    @GetMapping
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<LinkInspectionResponse> fetchInspection(@PathVariable("urlId") final long urlId) {
        return ResponseEntity.ok(linkInspectionService.inspection(urlId));
    }

    @GetMapping("/screenshot")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<?> fetchScreenshot(@PathVariable("urlId") final long urlId) {
        return linkInspectionService.screenshot(urlId)
                .<ResponseEntity<?>>map(screenshot -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(screenshot.getContentType()))
                        .header(HttpHeaders.CACHE_CONTROL, SCREENSHOT_CACHE)
                        .body(screenshot.getImage()))
                .orElseGet(() -> ProblemResponses.entity(HttpStatus.NOT_FOUND, NO_SCREENSHOT));
    }

    @PostMapping("/screenshot")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Audited(value = AuditAction.TAKE_SCREENSHOT, targetType = "Url")
    public ResponseEntity<Void> takeScreenshot(@PathVariable("urlId") final long urlId) {
        linkInspectionService.takeScreenshot(urlId);
        return ResponseEntity.accepted().build();
    }

}
