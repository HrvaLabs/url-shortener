package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.settings.SettingKey;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class OpenAiAppsChallengeController {

    private static final MediaType TEXT_PLAIN_UTF_8 = new MediaType("text", "plain", StandardCharsets.UTF_8);

    private final SettingsService settingsService;

    @GetMapping("/.well-known/openai-apps-challenge")
    public ResponseEntity<String> challenge() {
        final var token = StringUtils.strip(settingsService.text(SettingKey.OPENAI_APPS_CHALLENGE));
        if (StringUtils.isEmpty(token)) {
            return ResponseEntity.notFound().cacheControl(CacheControl.noStore()).build();
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(TEXT_PLAIN_UTF_8)
                .body(token);
    }

}
