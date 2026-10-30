package cc.hrva.urlshortener.controller;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.ClickRequest;
import cc.hrva.urlshortener.dto.CreateUrlDto;
import cc.hrva.urlshortener.dto.LinkPreviewResponse;
import cc.hrva.urlshortener.dto.UnlockResponse;
import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.dto.UrlSearchDto;
import cc.hrva.urlshortener.dto.UrlUpdateDto;
import cc.hrva.urlshortener.exception.FieldValidationException;
import cc.hrva.urlshortener.exception.RateLimitExceededException;
import cc.hrva.urlshortener.exception.UrlNotFoundException;
import cc.hrva.urlshortener.model.CustomDomain;
import cc.hrva.urlshortener.model.PeekUrl;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.enums.CreatedVia;
import cc.hrva.urlshortener.security.ClickRequestResolver;
import cc.hrva.urlshortener.security.ClientIpResolver;
import cc.hrva.urlshortener.service.LinkPreviewService;
import cc.hrva.urlshortener.service.QrCodeService;
import cc.hrva.urlshortener.service.UrlService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UrlControllerTest {

    private static final ClickRequest CLICK = ClickRequest.of("203.0.113.7", "UA", "https://t.co/x", "HR");
    private static final String TOO_LONG_DESTINATION = "https://example.com/" + "a".repeat(2029);
    private static final String DESTINATION_TOO_LONG = "Destination URL must be 2048 characters or fewer";

    private MockMvc mockMvc;

    @Mock
    private UrlService urlService;

    @Mock
    private QrCodeService qrCodeService;

    @Mock
    private LinkPreviewService linkPreviewService;

    @Mock
    private ClientIpResolver clientIpResolver;

    @Mock
    private ClickRequestResolver clickRequestResolver;

    @Mock
    private HttpServletRequest request;

    private final AppProperties appProperties = new AppProperties();

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        appProperties.setServerUrl("https://hrva.cc");
        this.mockMvc = MockMvcBuilders.standaloneSetup(new UrlController(urlService, qrCodeService, linkPreviewService, clientIpResolver, clickRequestResolver, request, appProperties))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    void shouldSaveUrl() throws Exception {
        final var url = Url.builder().id(1L).longUrl("https://example.com").shortUrl("ex").build();
        when(clientIpResolver.getClientIp(request)).thenReturn("203.0.113.7");
        when(urlService.saveUrlRouting(any(CreateUrlDto.class), eq("203.0.113.7"), any())).thenReturn(UrlResponse.from(url));

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(CreateUrlDto.builder().longUrl("https://example.com").build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.longUrl").value("https://example.com"));
    }

    @Test
    void shouldSaveUrlWithApiKey() throws Exception {
        final var url = Url.builder().id(1L).longUrl("https://example.com").shortUrl("ex").build();
        when(urlService.saveUrlWithApiKey(any(CreateUrlDto.class), eq("apikey"), any())).thenReturn(UrlResponse.from(url));

        mockMvc.perform(post("/api/v1/urls")
                        .header("X-Api-Key", "apikey")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(CreateUrlDto.builder().longUrl("https://example.com").build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortUrl").value("ex"));
    }

    @Test
    void createWithoutAKeyIsWeb() throws Exception {
        final var link = CreateUrlDto.builder().longUrl("https://example.com").build();
        when(clientIpResolver.getClientIp(request)).thenReturn("203.0.113.7");

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(link)))
                .andExpect(status().isOk());

        verify(urlService).saveUrlRouting(link, "203.0.113.7", CreatedVia.WEB);
        verify(urlService, never()).saveUrlWithApiKey(any(), any(), any());
    }

    @Test
    void createWithAKeyIsApiKey() throws Exception {
        final var link = CreateUrlDto.builder().longUrl("https://example.com").build();

        mockMvc.perform(post("/api/v1/urls")
                        .header("X-Api-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(link)))
                .andExpect(status().isOk());

        verify(urlService).saveUrlWithApiKey(link, "k", CreatedVia.API_KEY);
        verify(urlService, never()).saveUrlRouting(any(), any(), any());
    }

    @Test
    void bulkIsBulkWithOrWithoutAKey() throws Exception {
        final var link = CreateUrlDto.builder().longUrl("https://example.com/1").build();
        when(clientIpResolver.getClientIp(request)).thenReturn("203.0.113.7");

        mockMvc.perform(post("/api/v1/urls/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(link))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/urls/bulk")
                        .header("X-Api-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(link))))
                .andExpect(status().isOk());

        verify(urlService).saveUrlRouting(link, "203.0.113.7", CreatedVia.BULK);
        verify(urlService).saveUrlWithApiKey(link, "k", CreatedVia.BULK);
    }

    @Test
    void bulkChecksEveryDomainBeforeCreatingAny() throws Exception {
        final var links = List.of(
                CreateUrlDto.builder().longUrl("https://example.com/1").build(),
                CreateUrlDto.builder().longUrl("https://example.com/2").domain("elsewhere.example.com").build());
        org.mockito.Mockito.doThrow(new FieldValidationException("domain", "Choose one of your active domains, or leave it empty for hrva.cc."))
                .when(urlService).requireUsableDomains(links, "k");

        mockMvc.perform(post("/api/v1/urls/bulk")
                        .header("X-Api-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(links)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.domain").value("Choose one of your active domains, or leave it empty for hrva.cc."));

        verify(urlService, never()).saveUrlWithApiKey(any(), any(), any());
        verify(urlService, never()).saveUrlRouting(any(), any(), any());
    }

    @Test
    void aDomainOver253CharactersIsRefusedAsADomainError() throws Exception {
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(CreateUrlDto.builder().longUrl("https://example.com")
                                .domain("a".repeat(250) + ".com").build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.domain").value("Choose one of your active domains, or leave it empty for hrva.cc."));

        verifyNoInteractions(urlService);
    }

    @Test
    void aCreateRefusedByTheRateLimitIsA429WithTheReason() throws Exception {
        when(clientIpResolver.getClientIp(request)).thenReturn("203.0.113.7");
        when(urlService.saveUrlRouting(any(CreateUrlDto.class), eq("203.0.113.7"), any()))
                .thenThrow(new RateLimitExceededException("You've created a lot of links in a short time. Try again in an hour."));

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(CreateUrlDto.builder().longUrl("https://example.com").build())))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("You've created a lot of links in a short time. Try again in an hour."));
    }

    @Test
    void bulkSendsEveryLinkThroughTheLimitAndStopsWithA429() throws Exception {
        final var created = UrlResponse.from(Url.builder().id(1L).longUrl("https://example.com").shortUrl("ex").build());
        when(clientIpResolver.getClientIp(request)).thenReturn("203.0.113.7");
        when(urlService.saveUrlRouting(any(CreateUrlDto.class), eq("203.0.113.7"), any()))
                .thenReturn(created)
                .thenReturn(created)
                .thenThrow(new RateLimitExceededException("You've created a lot of links in a short time. Try again in an hour."));
        final var links = List.of(
                CreateUrlDto.builder().longUrl("https://example.com/1").build(),
                CreateUrlDto.builder().longUrl("https://example.com/2").build(),
                CreateUrlDto.builder().longUrl("https://example.com/3").build());

        mockMvc.perform(post("/api/v1/urls/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(links)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("You've created a lot of links in a short time. Try again in an hour."));

        verify(urlService, times(3)).saveUrlRouting(any(CreateUrlDto.class), eq("203.0.113.7"), any());
    }

    @Test
    void aDestinationOf2048CharactersIsAccepted() throws Exception {
        final var destination = "https://example.com/" + "a".repeat(2028);
        when(urlService.saveUrlRouting(any(CreateUrlDto.class), any(), any()))
                .thenReturn(UrlResponse.from(Url.builder().id(1L).longUrl(destination).shortUrl("ex").build()));

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(CreateUrlDto.builder().longUrl(destination).build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.longUrl").value(destination));
    }

    @Test
    void aDestinationOver2048CharactersIsRefusedWithTheLimit() throws Exception {
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(CreateUrlDto.builder().longUrl(TOO_LONG_DESTINATION).build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.longUrl").value(DESTINATION_TOO_LONG));

        verifyNoInteractions(urlService);
    }

    @Test
    void aBulkLinkOver2048CharactersIsRefusedWithTheLimitAndNothingIsCreated() throws Exception {
        final var links = List.of(
                CreateUrlDto.builder().longUrl("https://example.com/1").build(),
                CreateUrlDto.builder().longUrl(TOO_LONG_DESTINATION).build());

        mockMvc.perform(post("/api/v1/urls/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(links)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.longUrl").value(DESTINATION_TOO_LONG));

        verifyNoInteractions(urlService);
    }

    @Test
    void aBulkLinkWithoutADestinationIsRefusedAndNothingIsCreated() throws Exception {
        final var links = List.of(
                CreateUrlDto.builder().longUrl("https://example.com/1").build(),
                CreateUrlDto.builder().longUrl(" ").build());

        mockMvc.perform(post("/api/v1/urls/bulk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(links)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.longUrl").value("Long URL is required"));

        verifyNoInteractions(urlService);
    }

    @Test
    void aNewDestinationOver2048CharactersIsRefusedWithTheLimit() throws Exception {
        mockMvc.perform(put("/api/v1/urls/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(UrlUpdateDto.builder().id(1L).longUrl(TOO_LONG_DESTINATION).build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.longUrl").value(DESTINATION_TOO_LONG));

        verifyNoInteractions(urlService);
    }

    @Test
    void shouldFetchUrlByShort() throws Exception {
        final var url = Url.builder().id(1L).longUrl("https://example.com").build();
        when(clickRequestResolver.resolve(any())).thenReturn(CLICK);
        when(urlService.revealUrlAndRecordClick(null, "short", CLICK)).thenReturn(UrlResponse.from(url));

        mockMvc.perform(get("/api/v1/urls/short"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void qrCodeEncodesTheShortLinkWithTheQrMarker() throws Exception {
        when(urlService.peekUrlByShortUrl(null, "board")).thenReturn(PeekUrl.builder().shortUrl("board").build());
        when(qrCodeService.generateQrCode("https://hrva.cc/board?qr", 300, 300)).thenReturn(new byte[]{1});

        mockMvc.perform(get("/api/v1/urls/board/qr")).andExpect(status().isOk());
    }

    @Test
    void qrCodeShortLinkHasNoDoubleSlashWhenTheServerUrlEndsWithOne() throws Exception {
        appProperties.setServerUrl("https://hrva.cc/");
        when(urlService.peekUrlByShortUrl(null, "board")).thenReturn(PeekUrl.builder().shortUrl("board").build());
        when(qrCodeService.generateQrCode("https://hrva.cc/board?qr", 300, 300)).thenReturn(new byte[]{1});

        mockMvc.perform(get("/api/v1/urls/board/qr")).andExpect(status().isOk());
    }

    @Test
    void qrCodeSizeIsKeptWithinLimits() throws Exception {
        when(urlService.peekUrlByShortUrl(null, "board")).thenReturn(PeekUrl.builder().shortUrl("board").build());
        when(qrCodeService.generateQrCode("https://hrva.cc/board?qr", 1024, 1024)).thenReturn(new byte[]{1});
        when(qrCodeService.generateQrCode("https://hrva.cc/board?qr", 64, 64)).thenReturn(new byte[]{1});

        mockMvc.perform(get("/api/v1/urls/board/qr").param("size", "20000")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/urls/board/qr").param("size", "-5")).andExpect(status().isOk());
    }

    @Test
    void previewOfAPasswordProtectedLinkFetchesNothing() throws Exception {
        when(urlService.peekUrlByShortUrl(null, "board")).thenReturn(PeekUrl.builder().shortUrl("board").passwordProtected(true).build());

        mockMvc.perform(get("/api/v1/urls/board/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").doesNotExist());
        verify(linkPreviewService, never()).fetchPreview(any());
    }

    @Test
    void shouldPeekUrlByShortUrl() throws Exception {
        final var peekUrl = PeekUrl.builder().longUrl("https://example.com").shortUrl("ex").build();
        when(urlService.peekUrlByShortUrl(null, "short")).thenReturn(peekUrl);

        mockMvc.perform(get("/api/v1/urls/short/peek"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.longUrl").value("https://example.com"));
    }

    @Test
    void shouldUpdateUrl() throws Exception {
        final var url = Url.builder().id(1L).longUrl("https://example.com").build();
        when(urlService.updateUrl(any(UrlUpdateDto.class))).thenReturn(UrlResponse.from(url));

        mockMvc.perform(put("/api/v1/urls/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(UrlUpdateDto.builder().id(1L).build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void shouldGetAllMyUrls() throws Exception {
        final var url = Url.builder().id(1L).build();
        final var urlResponse = UrlResponse.from(url);
        final var urlList = Collections.singletonList(urlResponse);
        final var pageable = PageRequest.of(0, 20);
        final var urls = new PageImpl<>(urlList, pageable, urlList.size());
        when(urlService.getAllMyUrls(eq("apikey"), any(Pageable.class), any())).thenReturn(urls);

        mockMvc.perform(get("/api/v1/urls")
                        .header("X-Api-Key", "apikey")
                        .param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.size()").value(1));
    }

    @Test
    void shouldUnlockUrl() throws Exception {
        when(clickRequestResolver.resolve(any())).thenReturn(CLICK);
        when(urlService.unlockUrl(isNull(), eq("abc"), eq("pw"), eq(CLICK)))
                .thenReturn(new cc.hrva.urlshortener.dto.UnlockResponse("https://example.com"));

        mockMvc.perform(post("/api/v1/urls/abc/unlock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.longUrl").value("https://example.com"));
    }

    @Test
    void theLookupsReadTheLinkOnTheGivenDomain() throws Exception {
        final var onDomain = Url.builder().id(2L).shortUrl("menu").longUrl("https://b.example/menu").active(true)
                .domain(CustomDomain.builder().id(7L).hostname("go.example.com").build()).build();
        when(clickRequestResolver.resolve(any())).thenReturn(CLICK);
        when(urlService.revealUrlAndRecordClick("go.example.com", "menu", CLICK)).thenReturn(UrlResponse.from(onDomain).publicView());
        when(urlService.peekUrlByShortUrl("go.example.com", "menu"))
                .thenReturn(PeekUrl.builder().shortUrl("menu").longUrl("https://b.example/menu").domain("go.example.com").build());
        when(linkPreviewService.fetchPreview("https://b.example/menu")).thenReturn(new LinkPreviewResponse("https://b.example/menu", "B", null, null));

        mockMvc.perform(get("/api/v1/urls/menu").param("domain", "go.example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.domain.id").value(7))
                .andExpect(jsonPath("$.domain.hostname").value("go.example.com"));
        mockMvc.perform(get("/api/v1/urls/menu/peek").param("domain", "go.example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.longUrl").value("https://b.example/menu"))
                .andExpect(jsonPath("$.domain").doesNotExist());
        mockMvc.perform(get("/api/v1/urls/menu/preview").param("domain", "go.example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("B"));
    }

    @Test
    void withoutADomainTheLookupsMeanHrva() throws Exception {
        final var onHrva = Url.builder().id(1L).shortUrl("menu").longUrl("https://a.example/menu").active(true).build();
        when(clickRequestResolver.resolve(any())).thenReturn(CLICK);
        when(urlService.revealUrlAndRecordClick(null, "menu", CLICK)).thenReturn(UrlResponse.from(onHrva).publicView());

        mockMvc.perform(get("/api/v1/urls/menu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.domain").doesNotExist());
    }

    @Test
    void anUnknownOrTurnedOffDomainIsNotFound() throws Exception {
        when(clickRequestResolver.resolve(any())).thenReturn(CLICK);
        when(urlService.revealUrlAndRecordClick("off.example.com", "menu", CLICK)).thenThrow(new UrlNotFoundException("URL doesn't exist"));
        when(urlService.peekUrlByShortUrl("off.example.com", "menu")).thenThrow(new UrlNotFoundException("URL doesn't exist"));

        for (final var path : List.of("/api/v1/urls/menu", "/api/v1/urls/menu/peek", "/api/v1/urls/menu/preview", "/api/v1/urls/menu/qr")) {
            mockMvc.perform(get(path).param("domain", "off.example.com"))
                    .andExpect(status().isNotFound())
                    .andExpect(content().json("{\"error\":\"URL doesn't exist\"}"));
        }
        verifyNoInteractions(qrCodeService, linkPreviewService);
    }

    @Test
    void qrCodeOfADomainLinkEncodesTheDomainLink() throws Exception {
        when(urlService.peekUrlByShortUrl("GO.example.com", "menu")).thenReturn(PeekUrl.builder().shortUrl("menu").domain("go.example.com").build());
        when(qrCodeService.generateQrCode("https://go.example.com/menu?qr", 300, 300)).thenReturn(new byte[]{1});

        mockMvc.perform(get("/api/v1/urls/menu/qr").param("domain", "GO.example.com")).andExpect(status().isOk());

        verify(qrCodeService).generateQrCode("https://go.example.com/menu?qr", 300, 300);
    }

    @Test
    void unlockPassesTheDomainOn() throws Exception {
        when(clickRequestResolver.resolve(any())).thenReturn(CLICK);
        when(urlService.unlockUrl("go.example.com", "menu", "pw", CLICK)).thenReturn(new UnlockResponse("https://b.example/menu"));

        mockMvc.perform(post("/api/v1/urls/menu/unlock").param("domain", "go.example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.longUrl").value("https://b.example/menu"));
    }

    @Test
    void shouldGetMyTags() throws Exception {
        when(urlService.getMyTags(null)).thenReturn(java.util.List.of("work", "campaign"));

        mockMvc.perform(get("/api/v1/urls/tags"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("work"));
    }

    @Test
    void shouldGetAllUrls() throws Exception {
        final var url = Url.builder().id(1L).build();
        final var urlResponse = UrlResponse.from(url);
        final var urlList = Collections.singletonList(urlResponse);
        final var pageable = PageRequest.of(0, 20);
        final var urls = new PageImpl<>(urlList, pageable, urlList.size());
        when(urlService.getAllUrls(any(Pageable.class), any(UrlSearchDto.class))).thenReturn(urls);

        mockMvc.perform(get("/api/v1/urls/all").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.size()").value(1));
    }

    @Test
    void theAdminListTakesTheDeadDestinationFilterAndShowsTheHealth() throws Exception {
        final var dead = Url.builder().id(1L).shortUrl("menu").longUrl("https://gone.example/").active(true)
                .destinationDeadSince(LocalDateTime.of(2026, 10, 27, 2, 11, 40)).destinationLastStatus("404").build();
        final var pageable = PageRequest.of(0, 20);
        final var search = ArgumentCaptor.forClass(UrlSearchDto.class);
        when(urlService.getAllUrls(any(Pageable.class), search.capture()))
                .thenReturn(new PageImpl<>(List.of(UrlResponse.from(dead)), pageable, 1));

        mockMvc.perform(get("/api/v1/urls/all").param("deadDestination", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].destinationHealth.deadSince").value("2026-10-27T02:11:40"))
                .andExpect(jsonPath("$.content[0].destinationHealth.lastStatus").value("404"));

        assertThat(search.getValue().getDeadDestination()).isTrue();
    }

    @Test
    void shouldRevokeUrl() throws Exception {
        final var url = Url.builder().id(1L).active(false).build();
        when(urlService.revokeUrl(1L)).thenReturn(UrlResponse.from(url));

        mockMvc.perform(patch("/api/v1/urls/1/deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void shouldActivateUrl() throws Exception {
        final var url = Url.builder().id(1L).active(true).build();
        when(urlService.activateUrl(1L)).thenReturn(UrlResponse.from(url));

        mockMvc.perform(patch("/api/v1/urls/1/activate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void shouldDeleteUrl() throws Exception {
        mockMvc.perform(delete("/api/v1/urls/1"))
                .andExpect(status().isNoContent());

        verify(urlService).deleteUrl(1L);
    }
}
