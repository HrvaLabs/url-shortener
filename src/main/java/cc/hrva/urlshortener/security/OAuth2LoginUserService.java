package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.configuration.OAuth2ClientConfiguration;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class OAuth2LoginUserService implements OAuth2UserService<OAuth2UserRequest, OAuth2User> {

    public static final String EMAIL_VERIFIED = "email_verified";

    private static final String EMAIL = "email";
    private static final String EMAILS_URI = "https://api.github.com/user/emails?per_page=100";
    private static final MediaType GITHUB_JSON = MediaType.parseMediaType("application/vnd.github+json");
    private static final String API_VERSION_HEADER = "X-GitHub-Api-Version";
    private static final String API_VERSION = "2022-11-28";
    private static final String USER_AGENT = "hrva.cc-sign-in/1.0 (+https://hrva.cc/.well-known/security.txt)";
    private static final String INVALID_USER_INFO_RESPONSE = "invalid_user_info_response";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final ParameterizedTypeReference<List<GitHubEmail>> EMAIL_LIST = new ParameterizedTypeReference<>() {
    };

    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> userInfo;
    private final RestClient gitHubApi;

    @Autowired
    public OAuth2LoginUserService() {
        this(requestFactory());
    }

    private OAuth2LoginUserService(final ClientHttpRequestFactory requestFactory) {
        this(userInfo(requestFactory), RestClient.builder().requestFactory(requestFactory).build());
    }

    OAuth2LoginUserService(final OAuth2UserService<OAuth2UserRequest, OAuth2User> userInfo, final RestClient gitHubApi) {
        this.userInfo = userInfo;
        this.gitHubApi = gitHubApi;
    }

    @Override
    public OAuth2User loadUser(final OAuth2UserRequest userRequest) {
        final var user = userInfo.loadUser(userRequest);
        final var registration = userRequest.getClientRegistration();
        if (!OAuth2ClientConfiguration.GITHUB.equals(registration.getRegistrationId())) {
            return user;
        }

        final var email = verifiedPrimaryEmail(userRequest.getAccessToken());
        final var attributes = new LinkedHashMap<>(user.getAttributes());
        attributes.remove(EMAIL);
        email.ifPresent(address -> attributes.put(EMAIL, address));
        attributes.put(EMAIL_VERIFIED, email.isPresent());

        return new DefaultOAuth2User(user.getAuthorities(), attributes,
                registration.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName());
    }

    private Optional<String> verifiedPrimaryEmail(final OAuth2AccessToken accessToken) {
        return emails(accessToken).stream()
                .filter(email -> Boolean.TRUE.equals(email.primary()) && Boolean.TRUE.equals(email.verified()))
                .map(GitHubEmail::email)
                .filter(StringUtils::hasText)
                .findFirst();
    }

    private List<GitHubEmail> emails(final OAuth2AccessToken accessToken) {
        try {
            final var emails = gitHubApi.get()
                    .uri(EMAILS_URI)
                    .accept(GITHUB_JSON)
                    .header(API_VERSION_HEADER, API_VERSION)
                    .header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .headers(headers -> headers.setBearerAuth(accessToken.getTokenValue()))
                    .retrieve()
                    .body(EMAIL_LIST);

            return emails == null ? List.of() : emails;
        } catch (final RestClientException exception) {
            log.warn("GitHub sign-in failed: the email addresses could not be read exception={}", exception.getClass().getName());
            throw new OAuth2AuthenticationException(
                    new OAuth2Error(INVALID_USER_INFO_RESPONSE, "The GitHub account's email addresses could not be read", null), exception);
        }
    }

    private static DefaultOAuth2UserService userInfo(final ClientHttpRequestFactory requestFactory) {
        final var userInfoApi = new RestTemplate(requestFactory);
        userInfoApi.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        final var userInfo = new DefaultOAuth2UserService();
        userInfo.setRestOperations(userInfoApi);

        return userInfo;
    }

    private static ClientHttpRequestFactory requestFactory() {
        final var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        requestFactory.setReadTimeout(READ_TIMEOUT);

        return requestFactory;
    }

    record GitHubEmail(String email, Boolean primary, Boolean verified) {
    }

}
