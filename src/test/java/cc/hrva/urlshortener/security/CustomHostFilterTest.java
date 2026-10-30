package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.service.UrlService;
import cc.hrva.urlshortener.service.domain.DomainRoute;
import cc.hrva.urlshortener.service.domain.HostRouting;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.view.RedirectView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomHostFilterTest {

    private static final DomainRoute GO = new DomainRoute(7L, "go.example.com", CustomDomainStatus.ACTIVE, true);

    @Mock
    private HostRouting hostRouting;

    @Mock
    private UrlService urlService;

    @Mock
    private ClickRequestResolver clickRequestResolver;

    @Mock
    private AppProperties appProperties;

    @Mock
    private FilterChain chain;

    private CustomHostFilter filter;

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @BeforeEach
    void setUp() {
        filter = new CustomHostFilter(hostRouting, urlService, clickRequestResolver, appProperties, Clock.systemUTC());
    }

    @Test
    void anErrorDispatchOnACustomHostIsAPlain404() throws Exception {
        when(hostRouting.classify("go.example.com")).thenReturn(new HostRouting.Custom(GO));

        filter.doFilter(errorDispatch("go.example.com"), response, chain);

        assertPlain(404, "Not found");
        verify(chain, never()).doFilter(any(), any());
        verifyNoInteractions(urlService, clickRequestResolver);
    }

    @Test
    void anErrorDispatchOnAnUnknownHostIsAPlain404() throws Exception {
        when(hostRouting.classify("unknown.example.org")).thenReturn(new HostRouting.Unknown());

        filter.doFilter(errorDispatch("unknown.example.org"), response, chain);

        assertPlain(404, "Not found");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void anErrorDispatchOnAPrimaryHostReachesTheApp() throws Exception {
        final var request = errorDispatch("hrva.cc");
        when(hostRouting.classify("hrva.cc")).thenReturn(new HostRouting.Primary());

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void aFailureAnywhereWhileServingACustomHostIsAPlain500() throws Exception {
        when(hostRouting.classify("go.example.com")).thenReturn(new HostRouting.Custom(GO));
        when(appProperties.getFrontendUrl()).thenThrow(new IllegalStateException("broken settings"));

        filter.doFilter(request("/"), response, chain);

        assertPlain(500, "Something went wrong");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void aFailingRedirectIsAPlain500() throws Exception {
        when(hostRouting.classify("go.example.com")).thenReturn(new HostRouting.Custom(GO));
        when(urlService.redirectResultUrl(any(DomainRoute.class), any(), any())).thenThrow(new IllegalStateException("database down"));

        filter.doFilter(request("/menu"), response, chain);

        assertPlain(500, "Something went wrong");
    }

    @Test
    void aRedirectThatGivesNoLocationIsAPlain500() throws Exception {
        when(hostRouting.classify("go.example.com")).thenReturn(new HostRouting.Custom(GO));
        when(urlService.redirectResultUrl(any(DomainRoute.class), any(), any())).thenReturn(null);

        filter.doFilter(request("/menu"), response, chain);

        assertPlain(500, "Something went wrong");
        assertThat(response.getHeader(HttpHeaders.LOCATION)).isNull();
    }

    @Test
    void aRedirectIsWrittenWithTheNoStoreHeaders() throws Exception {
        when(hostRouting.classify("go.example.com")).thenReturn(new HostRouting.Custom(GO));
        when(urlService.redirectResultUrl(any(DomainRoute.class), any(), any())).thenReturn(new RedirectView("https://example.com/b"));

        filter.doFilter(request("/menu"), response, chain);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getHeader(HttpHeaders.LOCATION)).isEqualTo("https://example.com/b");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache, no-store, max-age=0, must-revalidate");
    }

    @Test
    void theRootGoesToTheFrontendHomePageAndIsNeverCached() throws Exception {
        when(hostRouting.classify("go.example.com")).thenReturn(new HostRouting.Custom(GO));
        when(appProperties.getFrontendUrl()).thenReturn("https://app.hrva.cc");

        filter.doFilter(request("/"), response, chain);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getHeader(HttpHeaders.LOCATION)).isEqualTo("https://app.hrva.cc/");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache, no-store, max-age=0, must-revalidate");
        verifyNoInteractions(urlService);
    }

    @Test
    void theRootKeepsASingleSlashWhenTheFrontendUrlEndsWithOne() throws Exception {
        when(hostRouting.classify("go.example.com")).thenReturn(new HostRouting.Custom(GO));
        when(appProperties.getFrontendUrl()).thenReturn("https://app.hrva.cc/");

        filter.doFilter(request("/"), response, chain);

        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getHeader(HttpHeaders.LOCATION)).isEqualTo("https://app.hrva.cc/");
    }

    private void assertPlain(final int status, final String body) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentAsString()).isEqualTo(body);
        assertThat(response.getContentType()).startsWith("text/plain");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-cache, no-store, max-age=0, must-revalidate");
        assertThat(response.getHeader(HttpHeaders.PRAGMA)).isEqualTo("no-cache");
        assertThat(response.getHeader(HttpHeaders.EXPIRES)).isEqualTo("0");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    private static MockHttpServletRequest request(final String path) {
        final var request = new MockHttpServletRequest("GET", path);
        request.setServerName("go.example.com");
        return request;
    }

    private static MockHttpServletRequest errorDispatch(final String host) {
        final var request = new MockHttpServletRequest("GET", "/error");
        request.setServerName(host);
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/menu");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 400);
        return request;
    }

}
