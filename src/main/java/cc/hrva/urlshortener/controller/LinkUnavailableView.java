package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.util.ProblemResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.View;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

public record LinkUnavailableView(TemplateEngine templateEngine, String frontendUrl, String shortLink, String reportTarget, String reason,
                                  String message) implements View {

    public static final String MISSING = "missing";
    public static final String BLOCKED = "blocked";
    public static final String CHECKING = "checking";
    public static final String EXPIRED = "expired";
    public static final String LIMIT = "limit";
    public static final String INACTIVE = "inactive";
    public static final String DOMAIN = "domain";

    private static final String TEMPLATE = "link_unavailable";
    private static final String CONTENT_TYPE = "text/html;charset=UTF-8";
    private static final int MAX_ALIAS_LENGTH = 32;
    private static final String SCHEME_SEPARATOR = "://";
    private static final String SLASH = "/";
    private static final Map<String, String> MESSAGES = Map.of(
            BLOCKED, "hrva.cc disabled this link because it led to a site reported for phishing, malware or scams.",
            CHECKING, "This link is being checked. Try again later.",
            EXPIRED, "This link has expired.",
            LIMIT, "This link has reached its visit limit.",
            INACTIVE, "Its owner has turned this link off.",
            DOMAIN, "This link's domain is turned off right now.");
    private static final List<MediaType> JSON_TYPES = List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON);

    public static LinkUnavailableView of(final TemplateEngine templateEngine, final String frontendUrl, final String serverUrl, final String alias,
                                         final String hostname, final String reason) {
        final var host = StringUtils.isBlank(hostname) ? hostOf(serverUrl) : hostname;
        final var shownAlias = StringUtils.left(StringUtils.defaultString(alias), MAX_ALIAS_LENGTH);
        final var shortLink = host + SLASH + shownAlias;
        final var message = MISSING.equals(reason) ? "There's no " + host + " link with this name." : MESSAGES.getOrDefault(reason, MESSAGES.get(INACTIVE));
        return new LinkUnavailableView(templateEngine, frontendUrl, shortLink, StringUtils.isBlank(hostname) ? shownAlias : shortLink, reason, message);
    }

    public HttpStatus status() {
        return MISSING.equals(reason) ? HttpStatus.NOT_FOUND : HttpStatus.GONE;
    }

    public String reportUrl() {
        return frontendUrl + "/report?s=" + URLEncoder.encode(reportTarget, StandardCharsets.UTF_8);
    }

    @Override
    public String getContentType() {
        return CONTENT_TYPE;
    }

    @Override
    public void render(final Map<String, ?> model, final HttpServletRequest request, final HttpServletResponse response) throws IOException {
        if (wantsJson(request)) {
            if (!response.isCommitted()) {
                response.reset();
            }
            LinkPageHeaders.apply(response);
            final var problem = ProblemResponses.of(status(), message);
            problem.setProperty("reason", reason);
            ProblemResponses.write(request, isHead(request) ? new BodylessResponse(response) : response, problem);
            return;
        }
        LinkPageHeaders.apply(response);
        final var body = templateEngine.process(TEMPLATE, context()).getBytes(StandardCharsets.UTF_8);
        response.setStatus(status().value());
        response.setContentType(CONTENT_TYPE);
        response.setContentLength(body.length);
        if (!isHead(request)) {
            response.getOutputStream().write(body);
        }
    }

    private Context context() {
        final var context = new Context();
        context.setVariable("shortLink", shortLink);
        context.setVariable("message", message);
        context.setVariable("reportUrl", reportUrl());
        context.setVariable("homeUrl", frontendUrl);
        context.setVariable("startUrl", frontendUrl.endsWith(SLASH) ? frontendUrl : frontendUrl + SLASH);
        return context;
    }

    private static boolean isHead(final HttpServletRequest request) {
        return HttpMethod.HEAD.matches(request.getMethod());
    }

    private static boolean wantsJson(final HttpServletRequest request) {
        final var accept = request.getHeader(HttpHeaders.ACCEPT);
        if (StringUtils.isBlank(accept)) {
            return false;
        }
        try {
            final var accepted = MediaType.parseMediaTypes(accept);
            return accepted.stream().noneMatch(type -> type.isCompatibleWith(MediaType.TEXT_HTML) && !type.isWildcardType())
                    && accepted.stream().anyMatch(type -> !type.isWildcardType() && JSON_TYPES.stream().anyMatch(type::isCompatibleWith));
        } catch (final InvalidMediaTypeException exception) {
            return false;
        }
    }

    private static String hostOf(final String serverUrl) {
        final var trimmed = StringUtils.removeEnd(StringUtils.defaultString(serverUrl), SLASH);
        return trimmed.contains(SCHEME_SEPARATOR) ? StringUtils.substringAfter(trimmed, SCHEME_SEPARATOR) : trimmed;
    }

    private static final class BodylessResponse extends jakarta.servlet.http.HttpServletResponseWrapper {

        private BodylessResponse(final HttpServletResponse response) {
            super(response);
        }

        @Override
        public jakarta.servlet.ServletOutputStream getOutputStream() {
            return new jakarta.servlet.ServletOutputStream() {
                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(final jakarta.servlet.WriteListener writeListener) {
                }

                @Override
                public void write(final int value) {
                }

                @Override
                public void write(final byte[] bytes, final int offset, final int length) {
                }
            };
        }

    }

}
