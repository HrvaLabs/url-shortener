package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.util.ProblemResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.View;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

public record LinksDownView(TemplateEngine templateEngine, String frontendUrl, String shortLink, String detail, Duration retryAfter) implements View {

    public static final String MESSAGE = "hrva.cc can't open links right now. Try again in a minute.";

    private static final String TEMPLATE = "links_down";
    private static final String CONTENT_TYPE = "text/html;charset=UTF-8";
    private static final String SLASH = "/";

    public static LinksDownView of(final TemplateEngine templateEngine, final String frontendUrl, final String serverUrl, final String alias,
                                   final String hostname, final String detail, final Duration retryAfter) {
        final var shortLink = LinkUnavailableView.of(templateEngine, frontendUrl, serverUrl, alias, hostname, LinkUnavailableView.MISSING).shortLink();
        return new LinksDownView(templateEngine, frontendUrl, shortLink, detail, retryAfter);
    }

    @Override
    public String getContentType() {
        return CONTENT_TYPE;
    }

    @Override
    public void render(final Map<String, ?> model, final HttpServletRequest request, final HttpServletResponse response) throws IOException {
        if (!response.isCommitted()) {
            response.reset();
        }
        ProblemResponses.retryAfter(response, retryAfter);
        if (LinkUnavailableView.wantsJson(request)) {
            final var answer = HttpMethod.HEAD.matches(request.getMethod()) ? new LinkUnavailableView.BodylessResponse(response) : response;
            ProblemResponses.write(request, answer, ProblemResponses.of(HttpStatus.SERVICE_UNAVAILABLE, detail));
            return;
        }
        LinkPageHeaders.apply(response);
        final var body = templateEngine.process(TEMPLATE, context()).getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(CONTENT_TYPE);
        response.setContentLength(body.length);
        if (!HttpMethod.HEAD.matches(request.getMethod())) {
            response.getOutputStream().write(body);
        }
    }

    private Context context() {
        final var context = new Context();
        context.setVariable("shortLink", shortLink);
        context.setVariable("message", MESSAGE);
        context.setVariable("homeUrl", frontendUrl);
        context.setVariable("startUrl", frontendUrl.endsWith(SLASH) ? frontendUrl : frontendUrl + SLASH);
        return context;
    }

}
