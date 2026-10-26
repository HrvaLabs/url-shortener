package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.bio.PublicBioPageResponse;
import cc.hrva.urlshortener.service.PublicBioPageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/public/bio-pages")
public class PublicBioPageController {

    private final PublicBioPageService publicBioPageService;

    @Operation(summary = "Read a public bio page", description = "The bio page at hrva.cc/@handle, for anyone: the profile and the buttons whose links are working, in order. No ids, owner or destinations. Never cached.")
    @ApiResponse(responseCode = "200", description = "The public bio page")
    @ApiResponse(responseCode = "404", description = "This page isn't available: unknown handle, hidden page, revoked handle or deactivated owner")
    @GetMapping("/{handle}")
    public ResponseEntity<PublicBioPageResponse> fetchBioPage(@PathVariable("handle") final String handle) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(publicBioPageService.find(handle));
    }

}
