package cc.hrva.urlshortener.security;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OAuth2LoginUserServiceTest {

    private static final String EMAILS = "https://api.github.com/user/emails";
    private static final String ACCESS_TOKEN = "gho_test-access-token";
    private static final ClientRegistration GITHUB = CommonOAuth2Provider.GITHUB.getBuilder("github")
            .clientId("test-github-client").clientSecret("test-github-secret").scope("user:email").build();
    private static final ClientRegistration GOOGLE = CommonOAuth2Provider.GOOGLE.getBuilder("google")
            .clientId("test-google-client").clientSecret("test-google-secret").scope("profile", "email").build();

    private final RestClient.Builder gitHubApi = RestClient.builder();
    private final MockRestServiceServer gitHub = MockRestServiceServer.bindTo(gitHubApi).build();

    @AfterEach
    void everyExpectedGitHubCallWasMade() {
        gitHub.verify();
    }

    @Test
    void aGitHubAccountWithoutAPublicEmailSignsInWithItsVerifiedPrimaryEmail() {
        expectEmails("""
                [{"email": "octocat@example.com", "primary": true, "verified": true, "visibility": null}]""");

        final var user = service(gitHubProfile(null)).loadUser(request(GITHUB));

        assertThat(user.<String>getAttribute("email")).isEqualTo("octocat@example.com");
        assertThat(user.<Boolean>getAttribute("email_verified")).isTrue();
        assertThat(user.getName()).isEqualTo("583231");
        assertThat(user.<String>getAttribute("login")).isEqualTo("octocat");
        assertThat(user.getAuthorities()).extracting(Object::toString).containsExactly("OAUTH2_USER");
    }

    @Test
    void theVerifiedPrimaryEmailWinsOverThePublicProfileEmail() {
        expectEmails("""
                [{"email": "public@example.com", "primary": false, "verified": true, "visibility": "public"},
                 {"email": "primary@example.com", "primary": true, "verified": true, "visibility": "private"}]""");

        final var user = service(gitHubProfile("public@example.com")).loadUser(request(GITHUB));

        assertThat(user.<String>getAttribute("email")).isEqualTo("primary@example.com");
        assertThat(user.<Boolean>getAttribute("email_verified")).isTrue();
    }

    @Test
    void anUnverifiedPrimaryEmailLeavesTheAccountWithNoEmailEvenWithAnotherVerifiedOne() {
        expectEmails("""
                [{"email": "primary@example.com", "primary": true, "verified": false, "visibility": "public"},
                 {"email": "other@example.com", "primary": false, "verified": true, "visibility": null}]""");

        final var user = service(gitHubProfile("primary@example.com")).loadUser(request(GITHUB));

        assertThat(user.getAttributes()).doesNotContainKey("email").containsEntry("email_verified", false);
        assertThat(user.getName()).isEqualTo("583231");
    }

    @Test
    void anAccountWithNoEmailsHasNoVerifiedEmail() {
        expectEmails("[]");

        final var user = service(gitHubProfile(null)).loadUser(request(GITHUB));

        assertThat(user.getAttributes()).doesNotContainKey("email").containsEntry("email_verified", false);
    }

    @Test
    void aGitHubErrorFailsTheSignIn() {
        gitHub.expect(requestTo(EMAILS)).andRespond(withServerError());

        assertThatThrownBy(() -> service(gitHubProfile("octocat@example.com")).loadUser(request(GITHUB)))
                .isInstanceOfSatisfying(OAuth2AuthenticationException.class,
                        exception -> assertThat(exception.getError().getErrorCode()).isEqualTo("invalid_user_info_response"));
    }

    @Test
    void aGoogleAccountIsReturnedAsGoogleSentIt() {
        final var google = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")),
                Map.of("sub", "1057", "email", "someone@gmail.com", "email_verified", false), "sub");

        assertThat(service(google).loadUser(request(GOOGLE))).isSameAs(google);
    }

    private void expectEmails(final String body) {
        gitHub.expect(requestTo(EMAILS))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github+json"))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andExpect(header(HttpHeaders.USER_AGENT, "hrva.cc-sign-in/1.0 (+https://hrva.cc/.well-known/security.txt)"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private OAuth2LoginUserService service(final OAuth2User profile) {
        final OAuth2UserService<OAuth2UserRequest, OAuth2User> userInfo = request -> profile;
        return new OAuth2LoginUserService(userInfo, gitHubApi.build());
    }

    private static OAuth2User gitHubProfile(final String publicEmail) {
        final var attributes = new HashMap<String, Object>();
        attributes.put("id", 583231);
        attributes.put("login", "octocat");
        attributes.put("name", "The Octocat");
        attributes.put("email", publicEmail);
        return new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")), attributes, "id");
    }

    private static OAuth2UserRequest request(final ClientRegistration registration) {
        return new OAuth2UserRequest(registration, new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, ACCESS_TOKEN,
                Instant.now(), Instant.now().plusSeconds(3600)));
    }

}
