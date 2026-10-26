package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.ReservedAliasDto;
import cc.hrva.urlshortener.dto.ReservedAliasResponse;
import cc.hrva.urlshortener.service.ReservedAliasService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
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
@RequestMapping("api/v1/admin/reserved-aliases")
public class AdminReservedAliasController {

    private final ReservedAliasService reservedAliasService;

    @GetMapping
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<List<ReservedAliasResponse>> fetchReservedAliases() {
        return ResponseEntity.ok(reservedAliasService.list());
    }

    @PostMapping
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<ReservedAliasResponse> reserveAlias(@Valid @RequestBody final ReservedAliasDto reservedAliasDto,
                                                              final Authentication authentication) {
        return ResponseEntity.ok(reservedAliasService.reserve(reservedAliasDto, authentication.getName()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<Void> unreserveAlias(@PathVariable("id") final long id, final Authentication authentication) {
        reservedAliasService.unreserve(id, authentication.getName());

        return ResponseEntity.noContent().build();
    }

}
