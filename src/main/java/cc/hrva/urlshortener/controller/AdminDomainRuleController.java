package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.DomainRuleCreatedResponse;
import cc.hrva.urlshortener.dto.DomainRuleDto;
import cc.hrva.urlshortener.dto.DomainRuleResponse;
import cc.hrva.urlshortener.model.enums.DomainRuleType;
import cc.hrva.urlshortener.service.ModerationService;
import io.swagger.v3.oas.annotations.Hidden;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/admin/domain-rules")
public class AdminDomainRuleController {

    private static final String VIA = "admin";

    private final ModerationService moderationService;

    @GetMapping
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<List<DomainRuleResponse>> fetchRules(@RequestParam(name = "type", required = false) final DomainRuleType type) {
        return ResponseEntity.ok(moderationService.domainRules(type));
    }

    @PostMapping
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<DomainRuleCreatedResponse> createRule(@RequestBody final DomainRuleDto domainRuleDto, final Authentication authentication) {
        return ResponseEntity.ok(moderationService.createDomainRule(domainRuleDto, authentication.getName(), VIA));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<Void> deleteRule(@PathVariable("id") final long id, final Authentication authentication) {
        moderationService.removeDomainRule(id, authentication.getName(), VIA);

        return ResponseEntity.noContent().build();
    }

}
