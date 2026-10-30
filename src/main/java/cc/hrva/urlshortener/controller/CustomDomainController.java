package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.domain.CustomDomainApplyDto;
import cc.hrva.urlshortener.dto.domain.CustomDomainResponse;
import cc.hrva.urlshortener.dto.domain.DomainRemovalResponse;
import cc.hrva.urlshortener.dto.domain.MyDomainsResponse;
import cc.hrva.urlshortener.model.enums.DomainLinkMode;
import cc.hrva.urlshortener.service.CustomDomainService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/users/me/domains")
public class CustomDomainController {

    private final CustomDomainService customDomainService;

    @Operation(summary = "Your custom domains", description = "The signed-in user's custom domains, newest first, with how many they may have and whether they can apply for another. Removed and rejected domains are listed for 30 days.")
    @ApiResponse(responseCode = "200", description = "The domains, the limit and how many count toward it")
    @GetMapping
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<MyDomainsResponse> mine() {
        return ResponseEntity.ok(customDomainService.mine());
    }

    @Operation(summary = "Apply for a custom domain", description = "Applies for a subdomain you own, like go.example.com, with a reason. An admin reviews it; nothing is set up before approval. 5 applications per day.")
    @ApiResponse(responseCode = "201", description = "Application received, waiting for review")
    @ApiResponse(responseCode = "400", description = "A field error, a domain that can't be used, or the limit of custom domains reached")
    @ApiResponse(responseCode = "409", description = "This domain has already been applied for")
    @ApiResponse(responseCode = "429", description = "Too many applications today")
    @ApiResponse(responseCode = "503", description = "Custom domains are turned off")
    @PostMapping
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<CustomDomainResponse> apply(@Valid @RequestBody final CustomDomainApplyDto customDomainApplyDto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(customDomainService.apply(customDomainApplyDto));
    }

    @Operation(summary = "One of your custom domains", description = "A custom domain of yours with its DNS record, setup steps and any problem that needs your attention.")
    @ApiResponse(responseCode = "200", description = "The domain")
    @ApiResponse(responseCode = "404", description = "No such domain of yours")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<CustomDomainResponse> get(@PathVariable final long id) {
        return ResponseEntity.ok(customDomainService.get(id));
    }

    @Operation(summary = "Retry a custom domain", description = "Sets up again a domain whose Cloudflare setup failed, or checks a domain "
            + "with a problem again now. At most once every 15 seconds per domain; more often, the stored status is answered.")
    @ApiResponse(responseCode = "200", description = "The domain after the retry")
    @ApiResponse(responseCode = "404", description = "No such domain of yours")
    @ApiResponse(responseCode = "409", description = "There's nothing to retry, or the domain can't be set up right now")
    @PostMapping("/{id}/retry")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<CustomDomainResponse> retry(@PathVariable final long id) {
        return ResponseEntity.ok(customDomainService.retry(id));
    }

    @Operation(summary = "Remove a custom domain", description = "Withdraws an application that is waiting for review; links is ignored then. Otherwise removes the domain: links=MOVE moves its links to hrva.cc, renaming any alias hrva.cc already has; links=DEACTIVATE (the default) turns them off.")
    @ApiResponse(responseCode = "200", description = "Removed, with what happened to the links")
    @ApiResponse(responseCode = "400", description = "links isn't MOVE or DEACTIVATE")
    @ApiResponse(responseCode = "404", description = "No such domain of yours")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<DomainRemovalResponse> remove(@PathVariable final long id, @RequestParam(name = "links", required = false) final String links) {
        return ResponseEntity.ok(customDomainService.remove(id, DomainLinkMode.fromParameter(links)));
    }

}
