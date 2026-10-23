package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.audit.Audited;
import cc.hrva.urlshortener.dto.BlocklistSourceDto;
import cc.hrva.urlshortener.dto.BlocklistSourceResponse;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.service.BlocklistSourceService;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import lombok.RequiredArgsConstructor;
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

@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/admin/blocklist-sources")
public class AdminBlocklistController {

    private final BlocklistSourceService blocklistSourceService;

    @GetMapping
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<List<BlocklistSourceResponse>> fetchSources() {
        return ResponseEntity.ok(blocklistSourceService.list());
    }

    @PostMapping
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Audited(AuditAction.CREATE_SOURCE)
    public ResponseEntity<BlocklistSourceResponse> createSource(@RequestBody final BlocklistSourceDto blocklistSourceDto) {
        return ResponseEntity.ok(blocklistSourceService.create(blocklistSourceDto));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Audited(AuditAction.UPDATE_SOURCE)
    public ResponseEntity<BlocklistSourceResponse> updateSource(@PathVariable("id") final long id,
                                                                @RequestBody final BlocklistSourceDto blocklistSourceDto) {
        return ResponseEntity.ok(blocklistSourceService.update(id, blocklistSourceDto));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Audited(AuditAction.DELETE_SOURCE)
    public ResponseEntity<Void> deleteSource(@PathVariable("id") final long id) {
        blocklistSourceService.delete(id);

        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/refresh")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    @Audited(AuditAction.REFRESH_SOURCE)
    public ResponseEntity<BlocklistSourceResponse> refreshSource(@PathVariable("id") final long id) {
        return ResponseEntity.ok(blocklistSourceService.refresh(id));
    }

}
