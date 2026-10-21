package cc.hrva.urlshortener.controller;

import java.time.Clock;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SecurityTxtController {

    private static final Duration VALIDITY = Duration.ofDays(365);
    private static final MediaType TEXT_PLAIN_UTF_8 = MediaType.parseMediaType("text/plain; charset=utf-8");
    private static final String SECURITY_TXT = """
            Contact: mailto:abuse@hrva.cc
            Expires: %s
            Preferred-Languages: en, hr
            Canonical: https://hrva.cc/.well-known/security.txt
            Policy: https://app.hrva.cc/terms
            """;

    private final Clock clock;

    @GetMapping("/.well-known/security.txt")
    public ResponseEntity<String> securityTxt() {
        final var expires = clock.instant().plus(VALIDITY).truncatedTo(ChronoUnit.SECONDS);

        return ResponseEntity.ok()
                .contentType(TEXT_PLAIN_UTF_8)
                .body(SECURITY_TXT.formatted(DateTimeFormatter.ISO_INSTANT.format(expires)));
    }

}
