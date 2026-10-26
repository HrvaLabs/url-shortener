package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.bio.PublicBioPageResponse;
import cc.hrva.urlshortener.security.ClickRequestResolver;
import cc.hrva.urlshortener.service.PublicBioPageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/public/bio-pages")
public class PublicBioPageController {

    private final PublicBioPageService publicBioPageService;
    private final ClickRequestResolver clickRequestResolver;

    @Operation(summary = "Read a public bio page", description = "The bio page at hrva.cc/@handle, for anyone: the profile and the buttons whose links are working, in order. No ids, owner or destinations. Never cached.")
    @ApiResponse(responseCode = "200", description = "The public bio page")
    @ApiResponse(responseCode = "404", description = "This page isn't available: unknown handle, hidden page, revoked handle or deactivated owner")
    @GetMapping("/{handle}")
    public ResponseEntity<PublicBioPageResponse> fetchBioPage(@PathVariable("handle") final String handle) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(publicBioPageService.find(handle));
    }

    @Operation(summary = "Count a bio page view", description = "The public page's first-party beacon, with no body. A human view counts once per "
            + "visitor per day (a salted daily hash in Redis, never the IP or User-Agent), a bot view counts apart every time, and at most 60 "
            + "views a minute count per network. Always 204, also for a page that isn't available, so the answer reveals nothing.")
    @ApiResponse(responseCode = "204", description = "Received; the view was counted or silently left out")
    @PostMapping("/{handle}/views")
    public ResponseEntity<Void> recordView(@PathVariable("handle") final String handle, final HttpServletRequest request) {
        try {
            publicBioPageService.recordView(handle, clickRequestResolver.resolve(request));
        } catch (final RuntimeException exception) {
            log.warn("Bio page view dropped pageId=unknown exception={}", exception.getClass().getName());
        }
        return ResponseEntity.noContent().build();
    }

}
