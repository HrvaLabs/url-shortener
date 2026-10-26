package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.bio.BioHandleAvailabilityResponse;
import cc.hrva.urlshortener.dto.bio.BioHandleDto;
import cc.hrva.urlshortener.dto.bio.BioPageCreateDto;
import cc.hrva.urlshortener.dto.bio.BioPageResponse;
import cc.hrva.urlshortener.dto.bio.BioPageUpdateDto;
import cc.hrva.urlshortener.service.BioPageService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/bio-pages")
public class BioPageController {

    private final BioPageService bioPageService;

    @Operation(summary = "Get your bio page", description = "The signed-in user's bio page with every button, its link's state and whether it shows on the page.")
    @ApiResponse(responseCode = "404", description = "You don't have a bio page yet")
    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<BioPageResponse> fetchMyBioPage() {
        return ResponseEntity.ok(bioPageService.mine());
    }

    @Operation(summary = "Create your bio page", description = "Create the signed-in user's bio page at hrva.cc/@handle. One page per account; creating a page counts toward the 5 handle changes per day.")
    @ApiResponse(responseCode = "201", description = "Bio page created")
    @ApiResponse(responseCode = "400", description = "The handle isn't 3 to 30 letters, numbers, hyphens and underscores")
    @ApiResponse(responseCode = "409", description = "You already have a bio page, or the handle is reserved or taken")
    @ApiResponse(responseCode = "429", description = "Too many handle changes today")
    @PostMapping("/me")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<BioPageResponse> createMyBioPage(@RequestBody final BioPageCreateDto bioPageCreateDto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(bioPageService.create(bioPageCreateDto));
    }

    @Operation(summary = "Save your bio page", description = "Replace the profile and the buttons as a whole. Buttons must be your own short links; a newly added link must be working. 60 saves per hour.")
    @ApiResponse(responseCode = "400", description = "Validation error, a link that isn't yours, the same link twice or a newly added link that isn't working")
    @ApiResponse(responseCode = "404", description = "You don't have a bio page yet")
    @ApiResponse(responseCode = "409", description = "Another save changed the page at the same time; reload and try again")
    @ApiResponse(responseCode = "429", description = "Too many saves in the last hour")
    @PutMapping("/me")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<BioPageResponse> updateMyBioPage(@Valid @RequestBody final BioPageUpdateDto bioPageUpdateDto) {
        return ResponseEntity.ok(bioPageService.update(bioPageUpdateDto));
    }

    @Operation(summary = "Change your handle", description = "Move the bio page to a new handle. The old handle is held for your page for 30 days. 5 handle changes per day.")
    @ApiResponse(responseCode = "400", description = "The handle isn't 3 to 30 letters, numbers, hyphens and underscores")
    @ApiResponse(responseCode = "404", description = "You don't have a bio page yet")
    @ApiResponse(responseCode = "409", description = "The handle is reserved or taken")
    @ApiResponse(responseCode = "429", description = "Too many handle changes today")
    @PutMapping("/me/handle")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<BioPageResponse> changeMyHandle(@RequestBody final BioHandleDto bioHandleDto) {
        return ResponseEntity.ok(bioPageService.changeHandle(bioHandleDto));
    }

    @Operation(summary = "Delete your bio page", description = "Delete the bio page and its buttons. Your links stay. The handle is held for 30 days, so nobody else can take it at once.")
    @ApiResponse(responseCode = "204", description = "Bio page deleted")
    @ApiResponse(responseCode = "404", description = "You don't have a bio page yet")
    @ApiResponse(responseCode = "409", description = "An admin hid the page, so only deleting the whole account removes it")
    @DeleteMapping("/me")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<Void> deleteMyBioPage() {
        bioPageService.delete();

        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Check a handle", description = "Whether a handle is free for your bio page, with the reason when it isn't. 120 checks per 10 minutes.")
    @ApiResponse(responseCode = "429", description = "Too many handle checks")
    @GetMapping("/handles/{handle}/availability")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<BioHandleAvailabilityResponse> checkHandle(@PathVariable("handle") final String handle) {
        return ResponseEntity.ok(bioPageService.availability(handle));
    }

}
