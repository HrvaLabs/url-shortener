package cc.hrva.urlshortener.configuration;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemErrorControllerTest {

    private final ProblemErrorController controller = new ProblemErrorController();

    @Test
    void aSentErrorWithAMessageBecomesAProblemWithThatDetailAndTheOriginalPath() {
        final var request = errorRequest(400, "invalid_request", "/oauth2/authorize");

        final var answer = controller.error(request);

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(answer.getBody().getDetail()).isEqualTo("invalid_request");
        assertThat(answer.getBody().getTitle()).isEqualTo("Bad Request");
        assertThat(answer.getBody().getInstance()).hasToString("/oauth2/authorize");
    }

    @Test
    void aSentErrorWithoutAMessageUsesTheReason() {
        final var answer = controller.error(errorRequest(503, null, "/connect/redirect"));

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(answer.getBody().getDetail()).isEqualTo("Service Unavailable");
    }

    @Test
    void aServerErrorNeverShowsItsMessage() {
        final var answer = controller.error(errorRequest(500, "NullPointerException in Foo", "/api/v1/urls"));

        assertThat(answer.getBody().getDetail()).isEqualTo("Internal server error");
    }

    @Test
    void aFilterThatCouldNotReachTheDatabaseIsA503() {
        final var request = errorRequest(500, null, "/api/v1/users/me");
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new org.springframework.transaction.CannotCreateTransactionException("no connection",
                new java.sql.SQLTransientConnectionException("request timed out after 3000ms")));

        final var answer = controller.error(request);

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(answer.getHeaders().getFirst("Retry-After")).isEqualTo("60");
        assertThat(answer.getBody().getDetail()).isEqualTo("hrva.cc can't reach its database right now. Try again in a minute.");
    }

    @Test
    void anErrorWithoutAStatusIsAServerError() {
        final var answer = controller.error(new MockHttpServletRequest("GET", "/error"));

        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static MockHttpServletRequest errorRequest(final int status, final String message, final String path) {
        final var request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
        request.setAttribute(RequestDispatcher.ERROR_MESSAGE, message);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, path);
        return request;
    }

}
