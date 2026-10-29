package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.ClickRequest;
import cc.hrva.urlshortener.security.ClickRequestResolver;
import cc.hrva.urlshortener.service.UrlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.RedirectView;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UrlRedirectControllerTest {

    private static final ClickRequest CLICK = ClickRequest.of("203.0.113.7", "UA", "https://t.co/x", "HR");

    private MockMvc mockMvc;

    @Mock
    private UrlService urlService;

    @Mock
    private ClickRequestResolver clickRequestResolver;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.standaloneSetup(new UrlRedirectController(urlService, clickRequestResolver, null)).build();
    }

    @Test
    void shouldRedirectToLongUrl() throws Exception {
        final var redirectView = new RedirectView("https://example.com");
        when(clickRequestResolver.resolve(any())).thenReturn(CLICK);
        when(urlService.redirectResultUrl("short", CLICK)).thenReturn(redirectView);

        mockMvc.perform(get("/short"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://example.com"));
    }

    @Test
    void theQrMarkerNeverReachesTheDestination() throws Exception {
        final var redirectView = new RedirectView();
        redirectView.setUrl("https://example.com/page?qr=keep&x=1");
        when(clickRequestResolver.resolve(any())).thenReturn(CLICK.withQrScan());
        when(urlService.redirectResultUrl("abc", CLICK.withQrScan())).thenReturn(redirectView);

        mockMvc.perform(get("/abc?qr"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://example.com/page?qr=keep&x=1"));
    }

    @Test
    void theRootMovesPermanentlyToTheFrontend() throws Exception {
        final var redirectView = new RedirectView("https://frontend.com/");
        when(urlService.redirectResultUrl(null, null)).thenReturn(redirectView);

        mockMvc.perform(get("/"))
                .andExpect(status().isMovedPermanently())
                .andExpect(redirectedUrl("https://frontend.com/"));

        verifyNoInteractions(clickRequestResolver);
    }
}
