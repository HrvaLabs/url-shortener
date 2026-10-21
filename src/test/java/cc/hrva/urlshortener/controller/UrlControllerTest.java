package cc.hrva.urlshortener.controller;

import java.util.Collections;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.CreateUrlDto;
import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.dto.UrlSearchDto;
import cc.hrva.urlshortener.dto.UrlUpdateDto;
import cc.hrva.urlshortener.model.PeekUrl;
import cc.hrva.urlshortener.model.Url;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UrlControllerTest {

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
    private HttpServletRequest request;

    private final AppProperties appProperties = new AppProperties();

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        appProperties.setServerUrl("https://hrva.cc");
        this.mockMvc = MockMvcBuilders.standaloneSetup(new UrlController(urlService, qrCodeService, linkPreviewService, clientIpResolver, request, appProperties))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    void shouldSaveUrl() throws Exception {
        final var url = Url.builder().id(1L).longUrl("https://example.com").shortUrl("ex").build();
        when(urlService.saveUrlRouting(any(CreateUrlDto.class))).thenReturn(UrlResponse.from(url));

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
        when(urlService.saveUrlWithApiKey(any(CreateUrlDto.class), eq("apikey"))).thenReturn(UrlResponse.from(url));

        mockMvc.perform(post("/api/v1/urls")
                        .header("X-Api-Key", "apikey")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(CreateUrlDto.builder().longUrl("https://example.com").build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortUrl").value("ex"));
    }

    @Test
    void shouldFetchUrlByShort() throws Exception {
        final var url = Url.builder().id(1L).longUrl("https://example.com").build();
        when(clientIpResolver.getClientIp(request)).thenReturn("127.0.0.1");
        when(urlService.checkIPUniquenessAndReturnUrl("short", "127.0.0.1")).thenReturn(UrlResponse.from(url));

        mockMvc.perform(get("/api/v1/urls/short"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void qrCodeEncodesTheShortLinkNotTheDestination() throws Exception {
        when(urlService.peekUrlByShortUrl("board")).thenReturn(PeekUrl.builder().shortUrl("board").build());
        when(qrCodeService.generateQrCode("https://hrva.cc/board", 300, 300)).thenReturn(new byte[]{1});

        mockMvc.perform(get("/api/v1/urls/board/qr")).andExpect(status().isOk());
    }

    @Test
    void qrCodeShortLinkHasNoDoubleSlashWhenTheServerUrlEndsWithOne() throws Exception {
        appProperties.setServerUrl("https://hrva.cc/");
        when(urlService.peekUrlByShortUrl("board")).thenReturn(PeekUrl.builder().shortUrl("board").build());
        when(qrCodeService.generateQrCode("https://hrva.cc/board", 300, 300)).thenReturn(new byte[]{1});

        mockMvc.perform(get("/api/v1/urls/board/qr")).andExpect(status().isOk());
    }

    @Test
    void qrCodeSizeIsKeptWithinLimits() throws Exception {
        when(urlService.peekUrlByShortUrl("board")).thenReturn(PeekUrl.builder().shortUrl("board").build());
        when(qrCodeService.generateQrCode("https://hrva.cc/board", 1024, 1024)).thenReturn(new byte[]{1});
        when(qrCodeService.generateQrCode("https://hrva.cc/board", 64, 64)).thenReturn(new byte[]{1});

        mockMvc.perform(get("/api/v1/urls/board/qr").param("size", "20000")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/urls/board/qr").param("size", "-5")).andExpect(status().isOk());
    }

    @Test
    void previewOfAPasswordProtectedLinkFetchesNothing() throws Exception {
        when(urlService.peekUrlByShortUrl("board")).thenReturn(PeekUrl.builder().shortUrl("board").passwordProtected(true).build());

        mockMvc.perform(get("/api/v1/urls/board/preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").doesNotExist());
        verify(linkPreviewService, never()).fetchPreview(any());
    }

    @Test
    void shouldPeekUrlByShortUrl() throws Exception {
        final var peekUrl = PeekUrl.builder().longUrl("https://example.com").shortUrl("ex").build();
        when(urlService.peekUrlByShortUrl("short")).thenReturn(peekUrl);

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
        when(clientIpResolver.getClientIp(request)).thenReturn("127.0.0.1");
        when(urlService.unlockUrl(eq("abc"), eq("pw"), eq("127.0.0.1")))
                .thenReturn(new cc.hrva.urlshortener.dto.UnlockResponse("https://example.com"));

        mockMvc.perform(post("/api/v1/urls/abc/unlock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"pw\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.longUrl").value("https://example.com"));
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
