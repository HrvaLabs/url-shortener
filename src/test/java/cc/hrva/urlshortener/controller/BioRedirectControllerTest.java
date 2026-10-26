package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.ClickRequest;
import cc.hrva.urlshortener.security.ClickRequestResolver;
import cc.hrva.urlshortener.service.UrlService;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.RedirectView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BioRedirectControllerTest {

    private static final ClickRequest CLICK = ClickRequest.of("203.0.113.7", "UA", "https://t.co/x", "HR");
    private static final String BIO_PAGES = "http://localhost:3000/bio/";

    private MockMvc mockMvc;

    @Mock
    private UrlService urlService;

    @Mock
    private ClickRequestResolver clickRequestResolver;

    @BeforeEach
    void setUp() {
        mockMvc = mockMvcFor("http://localhost:3000");
    }

    @Test
    void anAtPathGoesToTheBioPageNotTheShortLink() throws Exception {
        mockMvc.perform(get("/@Ana"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:3000/bio/ana"));

        verifyNoInteractions(urlService, clickRequestResolver);
    }

    @Test
    void theQueryStringIsDropped() throws Exception {
        mockMvc.perform(get("/@ana?qr"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:3000/bio/ana"));
        mockMvc.perform(get("/@ana?utm_source=x&next=https://evil.example"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:3000/bio/ana"));
    }

    @Test
    void oddCharactersAreEncodedNotFollowed() throws Exception {
        final var location = mockMvc.perform(get(URI.create("/@a%2F..%2Fevil")))
                .andExpect(status().isFound())
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);

        assertThat(location).startsWith(BIO_PAGES);
        assertThat(location.substring(BIO_PAGES.length())).doesNotContain("/").isEqualTo("a%2F..%2Fevil");
    }

    @Test
    void templateBracesInTheHandleAreEncoded() throws Exception {
        final var location = mockMvc.perform(get(URI.create("/@%7Bhandle%7D")))
                .andExpect(status().isFound())
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);

        assertThat(location).isEqualTo(BIO_PAGES + "%7Bhandle%7D");
    }

    @Test
    void aTrailingSlashOnTheFrontendUrlIsNotDoubled() throws Exception {
        mockMvcFor("http://localhost:3000/").perform(get("/@ana"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:3000/bio/ana"));
    }

    @Test
    void aPlainAliasStillReachesTheShortLink() throws Exception {
        when(clickRequestResolver.resolve(any())).thenReturn(CLICK);
        when(urlService.redirectResultUrl("abc", CLICK)).thenReturn(new RedirectView("https://example.com"));

        mockMvc.perform(get("/abc"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://example.com"));

        verify(urlService).redirectResultUrl("abc", CLICK);
    }

    @Test
    void aBareAtStillReachesTheShortLink() throws Exception {
        when(clickRequestResolver.resolve(any())).thenReturn(CLICK);
        when(urlService.redirectResultUrl("@", CLICK)).thenReturn(new RedirectView("http://localhost:3000/link-unavailable?reason=missing"));

        mockMvc.perform(get("/@"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost:3000/link-unavailable?reason=missing"));

        verify(urlService).redirectResultUrl("@", CLICK);
    }

    private MockMvc mockMvcFor(final String frontendUrl) {
        final var appProperties = new AppProperties();
        appProperties.setFrontendUrl(frontendUrl);
        return MockMvcBuilders.standaloneSetup(
                        new UrlRedirectController(urlService, clickRequestResolver, null),
                        new BioRedirectController(appProperties))
                .build();
    }

}
