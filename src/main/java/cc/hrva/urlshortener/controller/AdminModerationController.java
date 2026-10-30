package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.LinksAffectedResponse;
import cc.hrva.urlshortener.dto.UrlCheckDto;
import cc.hrva.urlshortener.dto.UrlCheckResponse;
import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.service.LinkCheckService;
import cc.hrva.urlshortener.service.ModerationService;
import cc.hrva.urlshortener.service.ScammerSignalService;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/admin")
public class AdminModerationController {

    private static final String VIA = "admin";
    private static final String URL_MISSING = "Enter a URL to check";

    private final ModerationService moderationService;
    private final ScammerSignalService scammerSignalService;
    private final LinkCheckService linkCheckService;

    @PostMapping("/users/{id}/block")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<LinksAffectedResponse> blockUser(@PathVariable("id") final long id, final Authentication authentication) {
        return ResponseEntity.ok(new LinksAffectedResponse(moderationService.blockUser(id, authentication.getName(), VIA, null)));
    }

    @PostMapping("/users/{id}/unblock")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<LinksAffectedResponse> unblockUser(@PathVariable("id") final long id, final Authentication authentication) {
        return ResponseEntity.ok(new LinksAffectedResponse(moderationService.reactivateUser(id, authentication.getName(), VIA)));
    }

    @DeleteMapping("/urls/{id}/warning-page")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<UrlResponse> clearWarningPage(@PathVariable("id") final long id, final Authentication authentication) {
        return ResponseEntity.ok(moderationService.clearWarningPage(id, authentication.getName()));
    }

    @GetMapping("/possible-scammers")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<List<Long>> fetchPossibleScammers() {
        return ResponseEntity.ok(scammerSignalService.possibleScammerIds().stream().sorted().toList());
    }

    @PostMapping("/url-check")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<UrlCheckResponse> checkUrl(@RequestBody final UrlCheckDto urlCheckDto) {
        final var url = Optional.ofNullable(StringUtils.stripToNull(urlCheckDto.url())).orElseThrow(() -> new ApiException(URL_MISSING));

        return ResponseEntity.ok(UrlCheckResponse.from(linkCheckService.explain(url), url));
    }

}
