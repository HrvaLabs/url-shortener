package cc.hrva.urlshortener.controller;

import io.swagger.v3.oas.annotations.Operation;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("api/changelog")
public class ChangelogController {

    private final Resource changelog;

    public ChangelogController() {
        this(new ClassPathResource("changelog.json"));
    }

    ChangelogController(final Resource changelog) {
        this.changelog = changelog;
    }

    @Operation(summary = "Get changelog", description = "Return the changelog JSON with all releases, features, and fixes.")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getChangelog() {
        try {
            return ResponseEntity.ok(changelog.getContentAsString(StandardCharsets.UTF_8));
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to read changelog.json", e);
        }
    }

}
