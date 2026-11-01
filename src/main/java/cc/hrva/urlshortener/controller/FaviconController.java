package cc.hrva.urlshortener.controller;

import io.swagger.v3.oas.annotations.Hidden;
import java.time.Duration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
public class FaviconController {

    private static final Resource FAVICON = new ClassPathResource("favicon.ico");
    private static final MediaType ICON = MediaType.parseMediaType("image/x-icon");
    private static final Duration BROWSER_CACHE = Duration.ofDays(30);

    @GetMapping("/favicon.ico")
    public ResponseEntity<Resource> favicon() {
        return ResponseEntity.ok()
                .contentType(ICON)
                .cacheControl(CacheControl.maxAge(BROWSER_CACHE).cachePublic())
                .body(FAVICON);
    }

}
