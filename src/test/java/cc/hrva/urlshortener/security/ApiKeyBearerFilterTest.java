package cc.hrva.urlshortener.security;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyBearerFilterTest {

    private static final String KEY = "hrva_" + "a".repeat(43);
    private static final String JWT = "eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJhbmFAaHJ2YS5jYyJ9.c2lnbmF0dXJl";

    private final ApiKeyBearerFilter filter = new ApiKeyBearerFilter();

    @Test
    void anApiKeySentAsABearerTokenReachesTheLinkApiAsXApiKeyAndNotAsASession() throws Exception {
        final var request = linkApi("/api/v1/urls");
        request.addHeader("Authorization", "Bearer " + KEY);

        final var seen = filtered(request);

        assertThat(seen.getHeader("X-Api-Key")).isEqualTo(KEY);
        assertThat(seen.getHeader("x-api-key")).isEqualTo(KEY);
        assertThat(Collections.list(seen.getHeaders("X-Api-Key"))).containsExactly(KEY);
        assertThat(seen.getHeader("Authorization")).isNull();
        assertThat(Collections.list(seen.getHeaders("Authorization"))).isEmpty();
        assertThat(Collections.list(seen.getHeaderNames())).anyMatch("X-Api-Key"::equalsIgnoreCase).noneMatch("Authorization"::equalsIgnoreCase);
    }

    @Test
    void everyLinkApiPathTakesIt() throws Exception {
        final var request = linkApi("/api/v1/urls/bulk");
        request.addHeader("Authorization", "Bearer " + KEY);

        assertThat(filtered(request).getHeader("X-Api-Key")).isEqualTo(KEY);
    }

    @Test
    void aJwtStaysASessionToken() throws Exception {
        final var request = linkApi("/api/v1/urls");
        request.addHeader("Authorization", "Bearer " + JWT);

        final var seen = filtered(request);

        assertThat(seen.getHeader("Authorization")).isEqualTo("Bearer " + JWT);
        assertThat(seen.getHeader("X-Api-Key")).isNull();
    }

    @Test
    void outsideTheLinkApiNothingChanges() throws Exception {
        final var request = linkApi("/api/v1/users/me");
        request.addHeader("Authorization", "Bearer " + KEY);

        final var seen = filtered(request);

        assertThat(seen.getHeader("Authorization")).isEqualTo("Bearer " + KEY);
        assertThat(seen.getHeader("X-Api-Key")).isNull();
    }

    @Test
    void theSameKeyInBothHeadersIsOneKey() throws Exception {
        final var request = linkApi("/api/v1/urls");
        request.addHeader("Authorization", "Bearer " + KEY);
        request.addHeader("X-Api-Key", KEY);

        final var seen = filtered(request);

        assertThat(seen.getHeader("X-Api-Key")).isEqualTo(KEY);
        assertThat(seen.getHeader("Authorization")).isNull();
    }

    @Test
    void twoDifferentKeysAreRefusedBeforeAnythingRuns() throws Exception {
        final var request = linkApi("/api/v1/urls");
        request.addHeader("Authorization", "Bearer " + KEY);
        request.addHeader("X-Api-Key", "hrva_" + "b".repeat(43));
        final var response = new MockHttpServletResponse();
        final var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("\"detail\":\"Send one API key: in X-Api-Key or as Authorization: Bearer, not two different ones.\"");
    }

    @Test
    void anXApiKeyWithAJwtKeepsBoth() throws Exception {
        final var request = linkApi("/api/v1/urls");
        request.addHeader("Authorization", "Bearer " + JWT);
        request.addHeader("X-Api-Key", KEY);

        final var seen = filtered(request);

        assertThat(seen.getHeader("Authorization")).isEqualTo("Bearer " + JWT);
        assertThat(seen.getHeader("X-Api-Key")).isEqualTo(KEY);
    }

    private static MockHttpServletRequest linkApi(final String path) {
        final var request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        return request;
    }

    private HttpServletRequest filtered(final MockHttpServletRequest request) throws Exception {
        final var chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return (HttpServletRequest) chain.getRequest();
    }

}
