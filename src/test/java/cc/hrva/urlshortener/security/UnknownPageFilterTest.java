package cc.hrva.urlshortener.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

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
            "GET|/.env|true",
            "HEAD|/.env|true",
            "GET|/.git|true",
            "GET|/config.php.bak|true",
            "GET|/config.php.bak/|true",
            "GET|/sitemap.xml|true",
            "GET|/favicon.png|true",
            "GET|/wp-login.php|true",
            "GET|/a b|true",
            "GET|/a%20b|true",
            "GET|/my.link|true",
            "GET|/%zz|true",
            "GET|/%00|true",
            "GET|/a%2Eb|true",
            "POST|/a/b|false",
            "PUT|/a/b|false",
            "DELETE|/a/b|false",
            "GET|/|false",
            "GET|/abc|false",
            "GET|/abc/|false",
            "GET|/abc_-1|false",
            "GET|/ABC_-1/|false",
            "GET|/%41bc|false",
            "GET|/%C3%A9|false",
            "GET|/%C4%8Duj%20mali|false",
            "GET|/%C2%B8bilosta|false",
            "GET|/caf%C3%A9|false",
            "GET|/caf%C3%A9/|false",
            "GET|/wp-admin|false",
            "POST|/.env|false",
            "PUT|/config.php.bak|false",
            "GET|/favicon.ico|false",
            "GET|/FAVICON.ICO|false",
            "GET|/robots.txt|false",
            "GET|/.well-known|false",
            "GET|/.well-known/|false",
            "GET|/swagger-ui.html|false",
            "GET|/@ana|false",
            "GET|/@ana/|false",
            "GET|/%40ana|false",
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

    @ParameterizedTest
    @MethodSource("allowedAliasCharacters")
    void everyAllowedAliasCharacterReachesTheLookup(final String character) {
        assertThat(UnknownPageFilter.isUnknownPage("GET", "/a" + character)).isFalse();
        assertThat(UnknownPageFilter.isUnknownPage("GET", "/" + character + character)).isFalse();
    }

    private static Stream<String> allowedAliasCharacters() {
        return "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-".chars().mapToObj(Character::toString);
    }

}
