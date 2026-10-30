package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.util.RedirectLocations;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.View;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

public record LinkWarningView(TemplateEngine templateEngine, String frontendUrl, String alias, String destination, String location) implements View {

    private static final String TEMPLATE = "link_warning";
    private static final String CONTENT_TYPE = "text/html;charset=UTF-8";
    private static final String CACHE_CONTROL = "no-store, no-transform";
    private static final String CONTENT_SECURITY_POLICY =
            "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
    private static final String ROBOTS = "noindex, nofollow";
    private static final String NO_REFERRER = "no-referrer";

    @Override
    public String getContentType() {
        return CONTENT_TYPE;
    }

    @Override
    public void render(final Map<String, ?> model, final HttpServletRequest request, final HttpServletResponse response) throws IOException {
        final var body = templateEngine.process(TEMPLATE, context()).getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(CONTENT_TYPE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        response.setHeader("X-Robots-Tag", ROBOTS);
        response.setHeader("Referrer-Policy", NO_REFERRER);
        response.setContentLength(body.length);
        if (!HttpMethod.HEAD.matches(request.getMethod())) {
            response.getOutputStream().write(body);
        }
    }

    public String host() {
        return RedirectLocations.hostOf(location);
    }

    public String reportUrl() {
        return frontendUrl + "/report?s=" + URLEncoder.encode(alias, StandardCharsets.UTF_8);
    }

    private Context context() {
        final var context = new Context();
        context.setVariable("destination", destination);
        context.setVariable("location", location);
        context.setVariable("host", host());
        context.setVariable("reportUrl", reportUrl());
        context.setVariable("homeUrl", frontendUrl);
        return context;
    }

}
