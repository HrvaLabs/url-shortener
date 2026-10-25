package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.audit.Audited;
import cc.hrva.urlshortener.dto.ReservedAliasDto;
import cc.hrva.urlshortener.dto.ReservedAliasResponse;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.service.ReservedAliasService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
    @Audited(AuditAction.RESERVE_ALIAS)
    public ResponseEntity<ReservedAliasResponse> reserveAlias(@Valid @RequestBody final ReservedAliasDto reservedAliasDto) {
        return ResponseEntity.ok(reservedAliasService.reserve(reservedAliasDto));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Audited(AuditAction.UNRESERVE_ALIAS)
    public ResponseEntity<Void> unreserveAlias(@PathVariable("id") final long id) {
        reservedAliasService.unreserve(id);

        return ResponseEntity.noContent().build();
    }

}
