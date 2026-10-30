package cc.hrva.urlshortener.service.settings;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.AppSetting;
import cc.hrva.urlshortener.repository.AppSettingRepository;
import cc.hrva.urlshortener.service.AuditLogService;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.impl.DefaultSettingsService;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

public final class SettingsServiceFixtures {

    private static final String ADMIN = "admin@hrva.cc";
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private SettingsServiceFixtures() {
    }

    public static DefaultSettingsService defaults(final AppProperties appProperties) {
        final var rows = new LinkedHashMap<String, AppSetting>();
        final var appSettingRepository = mock(AppSettingRepository.class);
        lenient().when(appSettingRepository.findAll()).thenAnswer(invocation -> List.copyOf(rows.values()));
        lenient().when(appSettingRepository.findById(anyString())).thenAnswer(invocation -> Optional.ofNullable(rows.get(invocation.<String>getArgument(0))));
        lenient().when(appSettingRepository.save(any(AppSetting.class))).thenAnswer(invocation -> store(rows, invocation.getArgument(0)));
        lenient().doAnswer(invocation -> rows.remove(invocation.<String>getArgument(0))).when(appSettingRepository).deleteById(anyString());
        return new DefaultSettingsService(appSettingRepository, new SettingDefaults(appProperties), mock(AuditLogService.class),
                mock(ApplicationEventPublisher.class), TransactionOperations.withoutTransaction(), Clock.systemUTC());
    }

    public static void turnOff(final SettingsService settingsService, final SettingKey key) {
        settingsService.change(key, NODES.booleanNode(false), ADMIN);
    }

    public static void turnOn(final SettingsService settingsService, final SettingKey key) {
        settingsService.change(key, NODES.booleanNode(true), ADMIN);
    }

    public static void change(final SettingsService settingsService, final SettingKey key, final int value) {
        settingsService.change(key, NODES.numberNode(value), ADMIN);
    }

    private static AppSetting store(final Map<String, AppSetting> rows, final AppSetting row) {
        rows.put(row.getSettingKey(), row);
        return row;
    }

}
