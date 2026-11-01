package cc.hrva.urlshortener.configuration;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.ResolvableType;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;

import static org.assertj.core.api.Assertions.assertThat;

class OAuth2TokenClientTest {

    private static final ResolvableType AUTHORIZATION_CODE_CLIENT =
            ResolvableType.forClassWithGenerics(OAuth2AccessTokenResponseClient.class, OAuth2AuthorizationCodeGrantRequest.class);

    private final List<String> userAgents = new CopyOnWriteArrayList<>();
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(OAuth2ClientAutoConfiguration.class))
            .withUserConfiguration(OAuth2ClientConfiguration.class)
            .withPropertyValues("OAUTH2_GOOGLE_CLIENT_ID=test-google-client", "OAUTH2_GOOGLE_CLIENT_SECRET=test-google-secret");
    private ExecutorService executor;
    private HttpServer tokenEndpoint;

    @BeforeEach
    void startTokenEndpoint() throws IOException {
        executor = Executors.newCachedThreadPool();
        tokenEndpoint = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        tokenEndpoint.setExecutor(executor);
        tokenEndpoint.createContext("/token", exchange -> {
            userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            exchange.getRequestBody().readAllBytes();
            final var body = "{\"access_token\":\"token\",\"token_type\":\"Bearer\",\"expires_in\":60}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (final var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        tokenEndpoint.start();
    }

    @AfterEach
    void stopTokenEndpoint() {
        tokenEndpoint.stop(0);
        executor.shutdownNow();
    }

    @Test
    void theSignInTokenExchangeUsesTheJdkHttpClient() {
        contextRunner.run(context -> {
            @SuppressWarnings("unchecked")
            final var client = (OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest>)
                    context.getBeanProvider(AUTHORIZATION_CODE_CLIENT).getIfUnique();
            assertThat(client).isNotNull();

            final var response = client.getTokenResponse(grantRequest());

            assertThat(response.getAccessToken().getTokenValue()).isEqualTo("token");
            assertThat(userAgents).singleElement().asString().startsWith("Java-http-client/");
        });
    }

    @Test
    void springSecuritysOwnDefaultWouldPickApacheNowThatItIsOnTheClasspath() {
        new RestClientAuthorizationCodeTokenResponseClient().getTokenResponse(grantRequest());

        assertThat(userAgents).singleElement().asString().startsWith("Apache-HttpClient/");
    }

    private OAuth2AuthorizationCodeGrantRequest grantRequest() {
        final var registration = ClientRegistration.withRegistrationId("google")
                .clientId("client")
                .clientSecret("secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://hrva.cc/login/oauth2/code/google")
                .authorizationUri("https://accounts.example/authorize")
                .tokenUri("http://127.0.0.1:" + tokenEndpoint.getAddress().getPort() + "/token")
                .build();
        final var request = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.example/authorize")
                .clientId("client")
                .redirectUri("https://hrva.cc/login/oauth2/code/google")
                .state("state")
                .build();
        final var response = OAuth2AuthorizationResponse.success("code").redirectUri("https://hrva.cc/login/oauth2/code/google").state("state").build();
        return new OAuth2AuthorizationCodeGrantRequest(registration, new OAuth2AuthorizationExchange(request, response));
    }

}
