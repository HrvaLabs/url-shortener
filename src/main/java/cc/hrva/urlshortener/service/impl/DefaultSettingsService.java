package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.settings.SettingResponse;
import cc.hrva.urlshortener.exception.SettingNotFoundException;
import cc.hrva.urlshortener.model.AppSetting;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.repository.AppSettingRepository;
import cc.hrva.urlshortener.service.AuditLogService;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent.SettingChanged;
import cc.hrva.urlshortener.service.settings.EmailKind;
import cc.hrva.urlshortener.service.settings.SettingDefaults;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.settings.SettingType;
import cc.hrva.urlshortener.service.settings.SettingValues;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import tools.jackson.databind.JsonNode;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultSettingsService implements SettingsService {

    private static final String TARGET_SETTING = "Setting";
    private static final String NOT_FOUND = "There is no setting %s.";
    private static final String AUDIT_DETAILS = "from: %s, to: %s";
    private static final String TURNED_ON = "turned on";
    private static final String TURNED_OFF = "turned off";
    private static final String CHANGED = "changed from %s to %s";
    private static final String RESET = "reset to its default (%s)";

    private final AppSettingRepository appSettingRepository;
    private final SettingDefaults settingDefaults;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionOperations transactionOperations;
    private final Clock clock;
    private final AtomicReference<Map<SettingKey, StoredValue>> stored = new AtomicReference<>();

    @Override
    public boolean isOn(final SettingKey key) {
        requireType(key, SettingType.SWITCH);
        return Boolean.TRUE.equals(current(key));
    }

    @Override
    public int number(final SettingKey key) {
        return optionalNumber(key).orElseThrow(() -> new IllegalStateException("Setting " + key + " has no number"));
    }

    @Override
    public OptionalInt optionalNumber(final SettingKey key) {
        requireType(key, SettingType.NUMBER);
        return current(key) instanceof Integer number ? OptionalInt.of(number) : OptionalInt.empty();
    }

    @Override
    public String text(final SettingKey key) {
        requireType(key, SettingType.TEXT);
        return (String) current(key);
    }

    @Override
    public List<String> addresses(final SettingKey key) {
        requireType(key, SettingType.ADDRESSES);
        return current(key) instanceof List<?> addresses ? addresses.stream().map(String::valueOf).toList() : List.of();
    }

    @Override
    public SettingKey settingKey(final String name) {
        return Arrays.stream(SettingKey.values())
                .filter(key -> key.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new SettingNotFoundException(NOT_FOUND.formatted(name)));
    }

    @Override
    public List<SettingResponse> list() {
        final var values = values();
        return Arrays.stream(SettingKey.values()).map(key -> response(key, values.get(key))).toList();
    }

    @Override
    public SettingResponse change(final SettingKey key, final JsonNode value, final String changedBy) {
        final var parsed = SettingValues.parse(key, value);
        final var change = transactionOperations.execute(status -> write(key, parsed, changedBy));
        return finish(key, change, AuditAction.CHANGE_SETTING, changedBy);
    }

    @Override
    public SettingResponse reset(final SettingKey key, final String changedBy) {
        final var change = transactionOperations.execute(status -> delete(key));
        return finish(key, change, AuditAction.RESET_SETTING, changedBy);
    }

    @Override
    public List<SettingKey> safetySwitchesOff() {
        return Arrays.stream(SettingKey.values())
                .filter(key -> key.isSafety() && key.getType() == SettingType.SWITCH)
                .filter(key -> !isOn(key))
                .toList();
    }

    @Override
    public Optional<SettingKey> emailSwitchOff(final EmailKind kind) {
        if (!isOn(SettingKey.SEND_EMAILS)) {
            return Optional.of(SettingKey.SEND_EMAILS);
        }
        return isOn(kind.getSetting()) ? Optional.empty() : Optional.of(kind.getSetting());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadAtStartup() {
        refresh();
    }

    @Override
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public synchronized void refresh() {
        try {
            stored.set(load());
        } catch (final RuntimeException exception) {
            stored.compareAndSet(null, Map.of());
            log.warn("Could not load the settings, keeping the last values exception={}", exception.getClass().getName());
        }
    }

    private Change write(final SettingKey key, final Object value, final String changedBy) {
        final var row = appSettingRepository.findById(key.name());
        final var defaultValue = settingDefaults.defaultOf(key);
        final var before = valueOf(key, row, defaultValue);
        if (Objects.equals(before, value)) {
            return null;
        }
        if (Objects.equals(value, defaultValue)) {
            appSettingRepository.deleteById(key.name());
            return new Change(before, value);
        }
        final var setting = row.orElseGet(() -> AppSetting.builder().settingKey(key.name()).build());
        setting.setSettingValue(SettingValues.stored(value));
        setting.setChangedBy(changedBy);
        setting.setChangedAt(LocalDateTime.now(clock));
        appSettingRepository.save(setting);
        return new Change(before, value);
    }

    private Change delete(final SettingKey key) {
        final var row = appSettingRepository.findById(key.name());
        if (row.isEmpty()) {
            return null;
        }
        final var defaultValue = settingDefaults.defaultOf(key);
        final var before = valueOf(key, row, defaultValue);
        appSettingRepository.deleteById(key.name());
        return new Change(before, defaultValue);
    }

    private static Object valueOf(final SettingKey key, final Optional<AppSetting> row, final Object defaultValue) {
        final var value = row.flatMap(existing -> storedValue(key, existing));
        return value.isPresent() ? value.get().value() : defaultValue;
    }

    private SettingResponse finish(final SettingKey key, final Change change, final AuditAction action, final String changedBy) {
        if (change != null) {
            refresh();
            auditLogService.log(changedBy, action, TARGET_SETTING, key.name(),
                    AUDIT_DETAILS.formatted(SettingValues.format(key, change.before()), SettingValues.format(key, change.after())));
            eventPublisher.publishEvent(new SettingChanged(key.getLabel(), changeText(key, change, action), changedBy));
        }
        return response(key, values().get(key));
    }

    private static String changeText(final SettingKey key, final Change change, final AuditAction action) {
        if (action == AuditAction.RESET_SETTING) {
            return RESET.formatted(SettingValues.format(key, change.after()));
        }
        if (key.getType() == SettingType.SWITCH) {
            return Boolean.TRUE.equals(change.after()) ? TURNED_ON : TURNED_OFF;
        }
        return CHANGED.formatted(SettingValues.format(key, change.before()), SettingValues.format(key, change.after()));
    }

    private Map<SettingKey, StoredValue> load() {
        final var values = new EnumMap<SettingKey, StoredValue>(SettingKey.class);
        for (final var row : appSettingRepository.findAll()) {
            Arrays.stream(SettingKey.values())
                    .filter(key -> key.name().equals(row.getSettingKey()))
                    .findFirst()
                    .flatMap(key -> storedValue(key, row))
                    .ifPresent(value -> values.put(value.key(), value));
        }
        return Collections.unmodifiableMap(values);
    }

    private static Optional<StoredValue> storedValue(final SettingKey key, final AppSetting row) {
        try {
            return Optional.of(new StoredValue(key, SettingValues.fromStored(key, row.getSettingValue()), row.getChangedBy(), row.getChangedAt()));
        } catch (final IllegalArgumentException exception) {
            log.warn("A stored setting no longer fits, using its default key={}", key);
            return Optional.empty();
        }
    }

    private Map<SettingKey, StoredValue> values() {
        final var values = stored.get();
        if (values != null) {
            return values;
        }
        refresh();
        return stored.get();
    }

    private Object current(final SettingKey key) {
        final var value = values().get(key);
        return value == null ? settingDefaults.defaultOf(key) : value.value();
    }

    private SettingResponse response(final SettingKey key, final StoredValue value) {
        final var defaultValue = settingDefaults.defaultOf(key);
        final var unavailableReason = settingDefaults.unavailableReason(key);
        return new SettingResponse(key, key.getGroup(), key.getType(), key.getLabel(), key.getDescription(), key.getParent(), key.isSafety(),
                value == null ? defaultValue : value.value(), defaultValue, value != null,
                value == null ? null : value.changedBy(), value == null || value.changedAt() == null ? null : value.changedAt().truncatedTo(ChronoUnit.SECONDS),
                key.getMin(), key.getMax(), key.isNullable(), key.getNoneLabel(), key.getUnit(), unavailableReason.isEmpty(),
                unavailableReason.orElse(null));
    }

    private static void requireType(final SettingKey key, final SettingType type) {
        if (key.getType() != type) {
            throw new IllegalArgumentException("Setting " + key + " is a " + key.getType() + ", not a " + type);
        }
    }

    private record StoredValue(SettingKey key, Object value, String changedBy, LocalDateTime changedAt) {}

    private record Change(Object before, Object after) {}

}
