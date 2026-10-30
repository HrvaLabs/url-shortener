package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.dto.settings.SettingChangeDto;
import cc.hrva.urlshortener.dto.settings.SettingResponse;
import cc.hrva.urlshortener.service.SettingsService;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Hidden
@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/admin/settings")
public class AdminSettingsController {

    private final SettingsService settingsService;

    @GetMapping
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<List<SettingResponse>> fetchSettings() {
        return ResponseEntity.ok(settingsService.list());
    }

    @PutMapping("/{key}")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<SettingResponse> changeSetting(@PathVariable("key") final String key, @RequestBody final SettingChangeDto settingChangeDto,
                                                         final Authentication authentication) {
        return ResponseEntity.ok(settingsService.change(settingsService.settingKey(key), settingChangeDto.value(), authentication.getName()));
    }

    @DeleteMapping("/{key}")
    @PreAuthorize("hasRole('ROLE_ADMIN')")
    public ResponseEntity<SettingResponse> resetSetting(@PathVariable("key") final String key, final Authentication authentication) {
        return ResponseEntity.ok(settingsService.reset(settingsService.settingKey(key), authentication.getName()));
    }

}
