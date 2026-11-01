package cc.hrva.urlshortener.configuration;

import cc.hrva.urlshortener.util.ProblemResponses;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProblemErrorController implements ErrorController {

    private static final String SERVER_ERROR = "Internal server error";

    @RequestMapping("${server.error.path:${error.path:/error}}")
    public ResponseEntity<ProblemDetail> error(final HttpServletRequest request) {
        final var status = statusOf(request);
        final var message = request.getAttribute(RequestDispatcher.ERROR_MESSAGE) instanceof String text ? text : null;
        final var detail = status == HttpStatus.INTERNAL_SERVER_ERROR ? SERVER_ERROR : StringUtils.defaultIfBlank(message, status.getReasonPhrase());
        final var problem = ProblemResponses.of(status, detail);
        if (request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String path) {
            problem.setInstance(URI.create(path));
        }
        return ResponseEntity.status(status).body(problem);
    }

    private static HttpStatus statusOf(final HttpServletRequest request) {
        final var code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        final var resolved = code instanceof Integer value ? HttpStatus.resolve(value) : null;
        return resolved == null ? HttpStatus.INTERNAL_SERVER_ERROR : resolved;
    }

}
