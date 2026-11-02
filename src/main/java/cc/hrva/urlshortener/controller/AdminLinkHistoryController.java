package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.LinkDestinationChangeResponse;
import cc.hrva.urlshortener.service.DestinationChangeService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/admin/urls")
public class AdminLinkHistoryController {

    private final DestinationChangeService destinationChangeService;

    @GetMapping("/{id}/destination-changes")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<Page<LinkDestinationChangeResponse>> destinationChanges(
            @PathVariable("id") final long id,
            @PageableDefault(size = 20) final Pageable pageable,
            @RequestParam(name = "search", required = false) final String search) {
        return ResponseEntity.ok(destinationChangeService.history(id, search, pageable));
    }

}
