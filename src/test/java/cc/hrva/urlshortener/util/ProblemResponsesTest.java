package cc.hrva.urlshortener.util;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemResponsesTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void writesAProblemWithTheRequestPathAsTheInstance() throws Exception {
        final var request = new MockHttpServletRequest("GET", "/api/v1/users/me");
        final var response = new MockHttpServletResponse();

        ProblemResponses.write(request, response, HttpStatus.UNAUTHORIZED, "Sign in first.");

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(JSON.readValue(response.getContentAsString(), Map.class)).containsExactly(
                Map.entry("type", "about:blank"),
                Map.entry("title", "Unauthorized"),
                Map.entry("status", 401),
                Map.entry("detail", "Sign in first."),
                Map.entry("instance", "/api/v1/users/me"));
    }

    @Test
    void writesFieldErrorsUnderErrorsWithTheFirstAsTheDetail() throws Exception {
        final var request = new MockHttpServletRequest("POST", "/api/v1/auth/register");
        final var response = new MockHttpServletResponse();
        final var errors = new LinkedHashMap<String, String>();
        errors.put("password", "Use at least 8 characters.");
        errors.put("email", "Enter an email address.");

        ProblemResponses.writeWithErrors(request, response, HttpStatus.BAD_REQUEST, errors);

        final var body = JSON.readValue(response.getContentAsString(), Map.class);
        assertThat(body).containsEntry("detail", "Use at least 8 characters.")
                .containsEntry("errors", Map.of("password", "Use at least 8 characters.", "email", "Enter an email address."));
    }

    @Test
    void aProblemForFieldErrorsCarriesThemAsAProperty() {
        final var problem = ProblemResponses.withErrors(HttpStatus.BAD_REQUEST, Map.of("alias", "Taken."));

        assertThat(problem.getDetail()).isEqualTo("Taken.");
        assertThat(problem.getProperties()).containsEntry("errors", Map.of("alias", "Taken."));
    }

    @Test
    void aProblemWithoutFieldErrorsSaysToCheckTheFields() {
        assertThat(ProblemResponses.withErrors(HttpStatus.BAD_REQUEST, Map.of()).getDetail()).isEqualTo(ProblemResponses.CHECK_FIELDS);
    }

    @Test
    void retryAfterIsWholeSecondsRoundedUpAndAtLeastOne() {
        assertThat(ProblemResponses.retryAfterSeconds(Duration.ofMillis(1_500))).isEqualTo("2");
        assertThat(ProblemResponses.retryAfterSeconds(Duration.ZERO)).isEqualTo("1");
        assertThat(ProblemResponses.retryAfterSeconds(null)).isEqualTo("60");
    }

    @Test
    void writesAnswerNothingOnceTheResponseIsCommitted() throws Exception {
        final var response = new MockHttpServletResponse();
        response.setCommitted(true);

        ProblemResponses.write(new MockHttpServletRequest("GET", "/x"), response, HttpStatus.NOT_FOUND, "Not found");

        assertThat(response.getContentAsString()).isEmpty();
    }

}
