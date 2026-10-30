package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.DomainBlockPreviewResponse;
import cc.hrva.urlshortener.dto.LinksAffectedResponse;
import cc.hrva.urlshortener.dto.campaign.CampaignDetailResponse;
import cc.hrva.urlshortener.dto.campaign.CampaignLinkResponse;
import cc.hrva.urlshortener.dto.campaign.CampaignResponse;
import cc.hrva.urlshortener.service.CampaignService;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/admin/campaigns")
public class AdminCampaignController {

    private final CampaignService campaignService;

    @GetMapping
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<Page<CampaignResponse>> fetchCampaigns(
            @PageableDefault(size = 20) final Pageable pageable,
            @RequestParam(name = "search", required = false) final String search) {
        return ResponseEntity.ok(campaignService.campaigns(search, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<CampaignDetailResponse> fetchCampaign(@PathVariable("id") final long id) {
        return ResponseEntity.ok(campaignService.detail(id));
    }

    @GetMapping("/{id}/links")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<Page<CampaignLinkResponse>> fetchLinks(
            @PathVariable("id") final long id,
            @PageableDefault(size = 20) final Pageable pageable,
            @RequestParam(name = "search", required = false) final String search) {
        return ResponseEntity.ok(campaignService.links(id, search, pageable));
    }

    @PostMapping("/{id}/allow")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<LinksAffectedResponse> allowShape(@PathVariable("id") final long id, final Authentication authentication) {
        return ResponseEntity.ok(new LinksAffectedResponse(campaignService.allow(id, authentication.getName())));
    }

    @PostMapping("/{id}/block-preview")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<List<DomainBlockPreviewResponse>> previewBlock(@PathVariable("id") final long id) {
        return ResponseEntity.ok(campaignService.blockPreview(id));
    }

}
