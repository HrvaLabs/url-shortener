package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.DomainBlockPreviewDto;
import cc.hrva.urlshortener.dto.DomainBlockPreviewResponse;
import cc.hrva.urlshortener.dto.DomainBulkBlockDto;
import cc.hrva.urlshortener.dto.DomainBulkBlockResponse;
import cc.hrva.urlshortener.dto.DomainRuleCreatedResponse;
import cc.hrva.urlshortener.dto.DomainRuleDto;
import cc.hrva.urlshortener.dto.DomainRuleResponse;
import cc.hrva.urlshortener.model.enums.DomainRuleType;
import cc.hrva.urlshortener.service.ModerationService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/admin/domain-rules")
public class AdminDomainRuleController {

    private static final String VIA = "admin";
    private static final String VIA_BULK = "admin-bulk";

    private final ModerationService moderationService;

    @GetMapping
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<Page<DomainRuleResponse>> fetchRules(
            @PageableDefault(size = 20) final Pageable pageable,
            @RequestParam(name = "type", required = false) final DomainRuleType type,
            @RequestParam(name = "search", required = false) final String search) {
        return ResponseEntity.ok(moderationService.domainRules(type, search, pageable));
    }

    @PostMapping
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<DomainRuleCreatedResponse> createRule(@RequestBody final DomainRuleDto domainRuleDto, final Authentication authentication) {
        return ResponseEntity.ok(moderationService.createDomainRule(domainRuleDto, authentication.getName(), VIA));
    }

    @PostMapping("/bulk-preview")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<List<DomainBlockPreviewResponse>> previewBulkBlock(@Valid @RequestBody final DomainBlockPreviewDto domainBlockPreviewDto) {
        return ResponseEntity.ok(moderationService.previewDomainBlocks(domainBlockPreviewDto.urlIds()));
    }

    @PostMapping("/bulk")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<DomainBulkBlockResponse> blockAll(@Valid @RequestBody final DomainBulkBlockDto domainBulkBlockDto,
                                                            final Authentication authentication) {
        return ResponseEntity.ok(moderationService.blockDomains(domainBulkBlockDto.domains(), domainBulkBlockDto.reason(), authentication.getName(),
                VIA_BULK));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<Void> deleteRule(@PathVariable("id") final long id, final Authentication authentication) {
        moderationService.removeDomainRule(id, authentication.getName(), VIA);

        return ResponseEntity.noContent().build();
    }

}
