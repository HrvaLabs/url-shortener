package cc.hrva.urlshortener.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import cc.hrva.urlshortener.security.ClickRequestResolver;
import cc.hrva.urlshortener.service.UrlService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

@Slf4j
@RestController
@RequiredArgsConstructor
public class UrlRedirectController {

    private final UrlService urlService;
    private final ClickRequestResolver clickRequestResolver;
    private final HttpServletRequest request;

    @GetMapping("/{short}")
    public RedirectView fetchUrlByShortUrlOrRedirectToFrontend(@PathVariable("short") final String shortUrl) {
        return urlService.redirectResultUrl(shortUrl, clickRequestResolver.resolve(request));
    }

    @GetMapping("/")
    public RedirectView rootRedirectToFrontend() {
        final var redirectView = urlService.redirectResultUrl(null, null);
        redirectView.setStatusCode(HttpStatus.MOVED_PERMANENTLY);
        return redirectView;
    }
}
