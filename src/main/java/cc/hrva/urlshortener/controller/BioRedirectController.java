package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import io.swagger.v3.oas.annotations.Operation;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriUtils;

import static java.nio.charset.StandardCharsets.UTF_8;

@RestController
@RequiredArgsConstructor
public class BioRedirectController {

    private static final String BIO_PATH = "/bio/";

    private final AppProperties appProperties;

    @GetMapping("/@{handle:.+}")
    @Operation(summary = "Open a bio page", description = "302 to the frontend bio page; the query string is dropped")
    public RedirectView redirectToBioPage(@PathVariable("handle") final String handle) {
        final var target = StringUtils.removeEnd(appProperties.getFrontendUrl(), "/")
                + BIO_PATH
                + UriUtils.encodePathSegment(handle.toLowerCase(Locale.ROOT), UTF_8);
        final var redirectView = new RedirectView(target);
        redirectView.setExposeModelAttributes(false);
        return redirectView;
    }

}
