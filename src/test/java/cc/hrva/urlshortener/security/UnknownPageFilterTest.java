package cc.hrva.urlshortener.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class UnknownPageFilterTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "GET|/a/b|true",
            "HEAD|/a/b|true",
            "GET|/wp-admin/setup-config.php|true",
            "GET|/@ana/links|true",
            "GET|/abc/def/|true",
            "GET|/abc//|true",
            "POST|/a/b|false",
            "PUT|/a/b|false",
            "DELETE|/a/b|false",
            "GET|/|false",
            "GET|/abc|false",
            "GET|/abc/|false",
            "GET|/favicon.ico|false",
            "GET|/@ana|false",
            "GET|/api/v1/urls/abc|false",
            "GET|/API/v1/urls/abc|false",
            "GET|/api/x|false",
            "GET|/actuator/health|false",
            "GET|/oauth2/authorization/google|false",
            "GET|/oauth2/connect/continue|false",
            "GET|/login/oauth2/code/github|false",
            "GET|/logout/x|false",
            "GET|/.well-known/security.txt|false",
            "GET|/.well-known/oauth-protected-resource/mcp|false",
            "GET|/mcp/anon/x|false",
            "GET|/swagger-ui/index.html|false",
            "GET|/v3/api-docs/x|false",
            "GET|/error/x|false",
            "GET|abc/def|false"})
    void onlyAGetOrHeadOfAPathBelowAnUnservedFirstSegmentIsAnUnknownPage(final String method, final String path, final boolean unknown) {
        assertThat(UnknownPageFilter.isUnknownPage(method, path)).isEqualTo(unknown);
    }

}
