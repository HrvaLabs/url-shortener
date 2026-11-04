package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.service.stats.RecentRequests;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.view.RedirectView;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ServerTimingOnTomcatTest.TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServerTimingOnTomcatTest {

    private static final String HEADER = "Server-Timing";
    private static final String FORMAT = "app;dur=\\d+\\.\\d";

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void aRedirectThatTheViewWritesCarriesTheHeaderOnce() throws Exception {
        final var answer = get("/go");

        assertThat(answer.statusCode()).isEqualTo(302);
        assertThat(answer.headers().firstValue("Location")).hasValue("https://example.com/");
        assertThat(answer.headers().allValues(HEADER)).singleElement().asString().matches(FORMAT);
    }

    @Test
    void aJsonAnswerCarriesTheHeaderOnce() throws Exception {
        final var answer = get("/json");

        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(answer.body()).contains("\"ok\"");
        assertThat(answer.headers().allValues(HEADER)).singleElement().asString().matches(FORMAT);
    }

    @Test
    void anUnknownPathAnswers404WithTheHeader() throws Exception {
        final var answer = get("/.env");

        assertThat(answer.statusCode()).isEqualTo(404);
        assertThat(answer.headers().allValues(HEADER)).singleElement().asString().matches(FORMAT);
    }

    @Test
    void aSentErrorCarriesTheHeaderThroughTheErrorPage() throws Exception {
        final var answer = get("/sent-401");

        assertThat(answer.statusCode()).isEqualTo(401);
        assertThat(answer.headers().allValues(HEADER)).singleElement().asString().matches(FORMAT);
    }

    @Test
    void anUnauthorizedAnswerWrittenWithoutAFlushCarriesTheHeader() throws Exception {
        final var answer = get("/refused");

        assertThat(answer.statusCode()).isEqualTo(401);
        assertThat(answer.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
        assertThat(answer.body()).isEqualTo("{\"error\":\"unauthorized\"}");
        assertThat(answer.headers().allValues(HEADER)).singleElement().asString().matches(FORMAT);
    }

    @Test
    void aFilterAfterItThatAnswersEarlyStillGetsTheHeader() throws Exception {
        final var answer = get("/early");

        assertThat(answer.statusCode()).isEqualTo(401);
        assertThat(answer.body()).isEqualTo("no");
        assertThat(answer.headers().allValues(HEADER)).singleElement().asString().matches(FORMAT);
    }

    @Test
    void aHeadRequestCarriesTheHeader() throws Exception {
        final var answer = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/json"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(answer.headers().allValues(HEADER)).singleElement().asString().matches(FORMAT);
    }

    private HttpResponse<String> get(final String path) throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @SpringBootConfiguration
    @ImportAutoConfiguration({
            TomcatServletWebServerAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            ErrorMvcAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class
    })
    @Import({ServerTimingFilter.class, RecentRequests.class, TestApplication.Routes.class})
    static class TestApplication {

        @Bean
        public FilterRegistrationBean<EarlyRefusalFilter> earlyRefusalFilterRegistration() {
            final var registration = new FilterRegistrationBean<>(new EarlyRefusalFilter());
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
            return registration;
        }

        @RestController
        static class Routes {

            @GetMapping("/go")
            public RedirectView go() {
                final var view = new RedirectView("https://example.com/");
                view.setStatusCode(HttpStatus.FOUND);
                return view;
            }

            @GetMapping("/json")
            public ResponseEntity<String> json() {
                return ResponseEntity.ok().header("Content-Type", "application/json").body("{\"ok\":true}");
            }

            @GetMapping("/sent-401")
            public void sent(final HttpServletResponse response) throws IOException {
                response.sendError(401, "no");
            }

            @GetMapping("/refused")
            public void refused(final HttpServletResponse response) throws IOException {
                response.setStatus(401);
                response.setHeader("WWW-Authenticate", "Bearer");
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"unauthorized\"}");
            }

        }

        static class EarlyRefusalFilter extends OncePerRequestFilter {

            @Override
            protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response, final FilterChain chain)
                    throws ServletException, IOException {
                if (request.getRequestURI().equals("/early")) {
                    response.setStatus(401);
                    response.getWriter().write("no");
                    return;
                }
                chain.doFilter(request, response);
            }

        }

    }

}
