package cc.hrva.urlshortener.util;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

public final class ProblemResponses {

    public static final String CHECK_FIELDS = "Check the highlighted fields.";
    public static final String ERRORS = "errors";
    public static final Duration DEFAULT_RETRY_AFTER = Duration.ofMinutes(1);

    private static final URI ABOUT_BLANK = URI.create("about:blank");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ProblemResponses() {}

    public static ProblemDetail of(final HttpStatus status, final String detail) {
        final var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(ABOUT_BLANK);
        problem.setTitle(status.getReasonPhrase());
        return problem;
    }

    public static ProblemDetail withErrors(final HttpStatus status, final Map<String, String> errors) {
        final var problem = of(status, errors.values().stream().findFirst().orElse(CHECK_FIELDS));
        problem.setProperty(ERRORS, new LinkedHashMap<>(errors));
        return problem;
    }

    public static ResponseEntity<ProblemDetail> entity(final HttpStatus status, final String detail) {
        return ResponseEntity.status(status).body(of(status, detail));
    }

    public static ResponseEntity<ProblemDetail> entityWithErrors(final HttpStatus status, final Map<String, String> errors) {
        return ResponseEntity.status(status).body(withErrors(status, errors));
    }

    public static void write(final HttpServletRequest request, final HttpServletResponse response, final HttpStatus status, final String detail)
            throws IOException {
        write(request, response, of(status, detail));
    }

    public static void writeWithErrors(final HttpServletRequest request, final HttpServletResponse response, final HttpStatus status,
                                       final Map<String, String> errors) throws IOException {
        write(request, response, withErrors(status, errors));
    }

    public static void write(final HttpServletRequest request, final HttpServletResponse response, final ProblemDetail problem) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        if (problem.getInstance() == null && request != null) {
            problem.setInstance(URI.create(request.getRequestURI()));
        }
        response.setStatus(problem.getStatus());
        final var body = JSON.writeValueAsString(bodyOf(problem)).getBytes(StandardCharsets.UTF_8);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    public static void retryAfter(final HttpServletResponse response, final Duration wait) {
        response.setHeader(HttpHeaders.RETRY_AFTER, retryAfterSeconds(wait));
    }

    public static String retryAfterSeconds(final Duration wait) {
        final var effective = wait == null ? DEFAULT_RETRY_AFTER : wait;
        final var seconds = effective.getSeconds() + (effective.getNano() > 0 ? 1 : 0);
        return String.valueOf(Math.max(1, seconds));
    }

    private static String reasonOf(final int status) {
        final var resolved = HttpStatus.resolve(status);
        return resolved == null ? null : resolved.getReasonPhrase();
    }

    private static Map<String, Object> bodyOf(final ProblemDetail problem) {
        final var body = new LinkedHashMap<String, Object>();
        body.put("type", (problem.getType() == null ? ABOUT_BLANK : problem.getType()).toString());
        body.put("title", problem.getTitle() == null ? reasonOf(problem.getStatus()) : problem.getTitle());
        body.put("status", problem.getStatus());
        body.put("detail", problem.getDetail());
        if (problem.getInstance() != null) {
            body.put("instance", problem.getInstance().toString());
        }
        if (problem.getProperties() != null) {
            body.putAll(problem.getProperties());
        }
        return body;
    }

}
