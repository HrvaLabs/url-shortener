package cc.hrva.urlshortener.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.CreateUrlDto;
import cc.hrva.urlshortener.dto.LinkPreviewResponse;
import cc.hrva.urlshortener.dto.UnlockResponse;
import cc.hrva.urlshortener.dto.UnlockUrlDto;
import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.dto.UrlSearchDto;
import cc.hrva.urlshortener.dto.UrlUpdateDto;
import cc.hrva.urlshortener.exception.ApiException;
import cc.hrva.urlshortener.model.PeekUrl;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.LinkPreviewService;
import cc.hrva.urlshortener.service.QrCodeService;
import cc.hrva.urlshortener.service.UrlService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/urls")
public class UrlController {

    private static final int MIN_QR_SIZE = 64;
    private static final int MAX_QR_SIZE = 1024;

    private final UrlService urlService;
    private final QrCodeService qrCodeService;
    private final LinkPreviewService linkPreviewService;
    private final ClientIpResolver clientIpResolver;
    private final HttpServletRequest request;
    private final AppProperties appProperties;

    @Operation(summary = "Create a short URL", description = "Create a short URL. Requires X-Api-Key header for programmatic access, otherwise optional for authenticated users.")
    @ApiResponse(responseCode = "400", description = "Validation error or bad request")
    @ApiResponse(responseCode = "409", description = "Short URL already exists")
    @ApiResponse(responseCode = "429", description = "Too many links created in the last hour")
    @PostMapping
    public ResponseEntity<UrlResponse> saveUrl(
            @Valid @RequestBody final CreateUrlDto createUrlDto,
            @RequestHeader(value = "X-Api-Key", required = false) final String apiKey) {
        if (apiKey != null) {
            return ResponseEntity.ok(urlService.saveUrlWithApiKey(createUrlDto, apiKey));
        }
        return ResponseEntity.ok(urlService.saveUrlRouting(createUrlDto, clientIpResolver.getClientIp(request)));
    }

    @Operation(summary = "Redirect to short URL", description = "Look up a short URL and return its details. Records a visit.")
    @ApiResponse(responseCode = "404", description = "URL not found")
    @GetMapping("/{short}")
    public ResponseEntity<UrlResponse> fetchUrlByShort(@PathVariable("short") final String shortUrl) {
        final var remoteAddress = clientIpResolver.getClientIp(request);
        return ResponseEntity.ok(urlService.checkIPUniquenessAndReturnUrl(shortUrl, remoteAddress));
    }

    @Operation(summary = "Peek at a short URL", description = "Quickly retrieve longUrl, shortUrl, and createDate without recording a visit.")
    @ApiResponse(responseCode = "404", description = "URL not found")
    @GetMapping("/{short}/peek")
    public ResponseEntity<PeekUrl> peekUrlByShortUrl(@PathVariable("short") final String shortUrl) {
        return ResponseEntity.ok(urlService.peekUrlByShortUrl(shortUrl));
    }

    @Operation(summary = "Unlock a password-protected URL", description = "Submit the password for a protected short URL to receive its destination and record the visit.")
    @ApiResponse(responseCode = "403", description = "Incorrect password")
    @ApiResponse(responseCode = "404", description = "URL not found")
    @PostMapping("/{short}/unlock")
    public ResponseEntity<UnlockResponse> unlockUrl(
            @PathVariable("short") final String shortUrl,
            @Valid @RequestBody final UnlockUrlDto dto) {
        final var clientIp = clientIpResolver.getClientIp(request);
        return ResponseEntity.ok(urlService.unlockUrl(shortUrl, dto.getPassword(), clientIp));
    }

    @Operation(summary = "Get rich link preview", description = "Fetch Open Graph metadata (title, description, image) for a short URL.")
    @ApiResponse(responseCode = "404", description = "URL not found")
    @GetMapping("/{short}/preview")
    public ResponseEntity<LinkPreviewResponse> previewUrl(@PathVariable("short") final String shortUrl) {
        final var urlInfo = urlService.peekUrlByShortUrl(shortUrl);
        if (urlInfo.isPasswordProtected()) {
            return ResponseEntity.ok(new LinkPreviewResponse(null, null, null, null));
        }

        return ResponseEntity.ok(linkPreviewService.fetchPreview(urlInfo.getLongUrl()));
    }

    @Operation(summary = "Generate QR code", description = "Generate a QR code PNG for the short URL. Optional size parameter.")
    @ApiResponse(responseCode = "404", description = "URL not found")
    @GetMapping(value = "/{short}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> generateQrCode(
            @PathVariable("short") final String shortUrl,
            @RequestParam(defaultValue = "300") final int size) {
        final var urlInfo = urlService.peekUrlByShortUrl(shortUrl);
        final var boundedSize = Math.clamp(size, MIN_QR_SIZE, MAX_QR_SIZE);
        final var shortLink = StringUtils.removeEnd(appProperties.getServerUrl(), "/") + "/" + urlInfo.getShortUrl();
        final var qrBytes = qrCodeService.generateQrCode(shortLink, boundedSize, boundedSize);

        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(qrBytes);
    }

    @Operation(summary = "Bulk create short URLs", description = "Create multiple short URLs in a single request. Requires ROLE_USER.")
    @ApiResponse(responseCode = "400", description = "Validation error or bad request")
    @ApiResponse(responseCode = "409", description = "Short URL already exists")
    @ApiResponse(responseCode = "429", description = "Too many links created in the last hour")
    @PostMapping("/bulk")
    @PreAuthorize("hasRole('ROLE_USER')")
    public ResponseEntity<List<UrlResponse>> createBulkUrls(
            @Valid @RequestBody final List<@Valid CreateUrlDto> createUrlDtos,
            @RequestHeader(value = "X-Api-Key", required = false) final String apiKey) {
        if (createUrlDtos == null || createUrlDtos.isEmpty()) {
            throw new ApiException("Request must contain at least one URL");
        }
        final var clientIp = clientIpResolver.getClientIp(request);
        final var urls = createUrlDtos.stream()
                .map(dto -> apiKey != null
                        ? urlService.saveUrlWithApiKey(dto, apiKey)
                        : urlService.saveUrlRouting(dto, clientIp))
                .toList();

        return ResponseEntity.ok(urls);
    }

    @Operation(summary = "Update a short URL", description = "Update visit limit and/or expiration date of an existing short URL. Authentication required.")
    @ApiResponse(responseCode = "400", description = "Validation error or bad request")
    @ApiResponse(responseCode = "404", description = "URL not found")
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ROLE_ADMIN', 'ROLE_USER')")
    public ResponseEntity<UrlResponse> updateUrl(
            @PathVariable final Long id,
            @Valid @RequestBody final UrlUpdateDto urlUpdateDto) {
        urlUpdateDto.setId(id);
        return ResponseEntity.ok(urlService.updateUrl(urlUpdateDto));
    }

    @Operation(summary = "Export my URLs as CSV", description = "Download your URLs as a CSV file. Requires ROLE_USER.")
    @GetMapping("/export")
    @PreAuthorize("hasRole('ROLE_USER')")
    public ResponseEntity<byte[]> exportMyUrls(
            @RequestHeader(value = "X-Api-Key", required = false) final String apiKey) {
        final var csv = urlService.exportMyUrlsAsCsv(apiKey);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=my-urls.csv")
                .contentType(MediaType.TEXT_PLAIN)
                .body(csv);
    }

    @Operation(summary = "Get my URLs", description = "Retrieve paginated list of URLs owned by the authenticated user. Supports filtering by search, active, expired, dateFrom, and dateTo. Requires ROLE_USER.")
    @GetMapping
    @PreAuthorize("hasRole('ROLE_USER')")
    public ResponseEntity<Page<UrlResponse>> getAllMyUrls(
            @RequestHeader(value = "X-Api-Key", required = false) final String apiKey,
            @PageableDefault(size = 20) final Pageable pageable,
            @ModelAttribute final UrlSearchDto search) {
        return ResponseEntity.ok(urlService.getAllMyUrls(apiKey, pageable, search));
    }

    @Operation(summary = "Get my tags", description = "Retrieve the distinct tags used across the authenticated user's URLs.")
    @GetMapping("/tags")
    @PreAuthorize("hasRole('ROLE_USER')")
    public ResponseEntity<java.util.List<String>> getMyTags(
            @RequestHeader(value = "X-Api-Key", required = false) final String apiKey) {
        return ResponseEntity.ok(urlService.getMyTags(apiKey));
    }

    @io.swagger.v3.oas.annotations.Hidden
    @Operation(summary = "Get all URLs (admin)", description = "Retrieve paginated list of all URLs in the system. Supports filtering by search, active, expired, dateFrom, and dateTo. Requires ROLE_ADMIN.")
    @GetMapping("/all")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<Page<UrlResponse>> getAllUrls(
            @PageableDefault(size = 20) final Pageable pageable,
            @ModelAttribute final UrlSearchDto search) {
        return ResponseEntity.ok(urlService.getAllUrls(pageable, search));
    }

    @Operation(summary = "Deactivate a short URL", description = "Deactivate a short URL by its ID. Authentication required.")
    @ApiResponse(responseCode = "404", description = "URL not found")
    @PatchMapping("/{id}/deactivate")
    public ResponseEntity<UrlResponse> revokeUrl(@PathVariable("id") final Long id) {
        return ResponseEntity.ok(urlService.revokeUrl(id));
    }

    @Operation(summary = "Activate a short URL", description = "Reactivate a previously deactivated short URL by its ID. Authentication required.")
    @ApiResponse(responseCode = "404", description = "URL not found")
    @PatchMapping("/{id}/activate")
    public ResponseEntity<UrlResponse> activateUrl(@PathVariable("id") final Long id) {
        return ResponseEntity.ok(urlService.activateUrl(id));
    }

    @Operation(summary = "Delete a short URL", description = "Permanently delete a short URL by its ID. Authentication required.")
    @ApiResponse(responseCode = "404", description = "URL not found")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUrl(@PathVariable("id") final Long id) {
        urlService.deleteUrl(id);

        return ResponseEntity.noContent().build();
    }

}
