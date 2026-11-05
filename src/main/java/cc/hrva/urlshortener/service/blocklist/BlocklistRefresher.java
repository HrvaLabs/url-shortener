package cc.hrva.urlshortener.service.blocklist;

import cc.hrva.urlshortener.client.FetchResult;
import cc.hrva.urlshortener.client.PublicHttpFetcher;
import cc.hrva.urlshortener.exception.BlocklistFormatException;
import cc.hrva.urlshortener.exception.CommonException;
import cc.hrva.urlshortener.model.BlocklistSource;
import cc.hrva.urlshortener.model.enums.SourceKind;
import cc.hrva.urlshortener.repository.BlocklistSourceRepository;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.service.settings.SettingKey;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class BlocklistRefresher {

    private static final long MAX_BYTES = 100L * 1024 * 1024;
    private static final long MAX_API_ANSWER_BYTES = 64L * 1024;
    private static final int MAX_ENTRIES = 5_000_000;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final String ERROR_SEPARATOR = "; ";
    private static final int MAX_ETAG_LENGTH = 255;
    private static final int MAX_LAST_MODIFIED_LENGTH = 100;
    private static final long MIN_GUARDED_ENTRIES = 1000;
    private static final double MIN_KEPT_SHARE = 0.5;
    private static final String SHRUNK = "The new download has %,d entries, less than half of the %,d loaded before. Kept the old list.";
    private static final String NO_DOWNLOAD_LINK = "No download link found. Use the Tranco API address of the latest list.";
    private static final String NO_LIST_SIZE = "The download link doesn't end in a list size.";
    private static final Pattern LIST_SIZE = Pattern.compile("/\\d+$");
    private static final JsonMapper JSON = JsonMapper.shared();

    private final BlocklistSourceRepository blocklistSourceRepository;
    private final BlocklistStore blocklistStore;
    private final BlocklistMemoryGuard blocklistMemoryGuard;
    private final PublicHttpFetcher publicHttpFetcher;
    private final BlocklistParser blocklistParser;
    private final LinkListParser linkListParser;
    private final TopSitesParser topSitesParser;
    private final EndingsParser endingsParser;
    private final BrandSitesParser brandSitesParser;
    private final SettingsService settingsService;
    private final ApplicationEventPublisher eventPublisher;
    private final BlocklistHealth blocklistHealth;
    private final Set<Long> refreshing = ConcurrentHashMap.newKeySet();

    public BlocklistSource refresh(final BlocklistSource source, final RefreshTrigger trigger) {
        if (!refreshing.add(source.getId())) {
            log.info("Blocklist source {} is already refreshing", source.getName());
            return source;
        }
        try {
            final var kind = source.getKind();
            final var download = download(source, trigger);
            final var current = blocklistSourceRepository.findById(source.getId());
            if (current.isEmpty() || !current.get().isEnabled() || current.get().getKind() != kind) {
                log.info("Blocklist source {} was disabled, deleted or changed kind while it downloaded, discarding the download",
                        source.getName());
                return current.orElse(source);
            }
            return record(current.get(), download);
        } finally {
            refreshing.remove(source.getId());
        }
    }

    private Download download(final BlocklistSource source, final RefreshTrigger trigger) {
        final var attempts = attemptsOf(source);
        final var errors = new ArrayList<String>();
        for (final var attempt : attempts) {
            try {
                final var limit = entryLimitOf(source.getKind());
                final var list = source.getKind().ranked()
                        ? new Attempt(attempt.label(), topSitesUrl(attempt.url(), limit))
                        : attempt;
                final var conditional = sendsValidators(source, list.url());
                final var fetched = publicHttpFetcher.fetch(list.url(), MAX_BYTES,
                        conditional ? source.getEtag() : null, conditional ? source.getLastModified() : null, REQUEST_TIMEOUT);
                if (fetched.notModified()) {
                    log.info("Blocklist source {} is unchanged ({})", source.getName(), attempt.label());
                    return new Download(errors, null, true);
                }
                final var parsed = parse(source.getKind(), fetched.body(), limit);
                refuseShrunkenList(source, parsed, trigger, limit);
                return new Download(errors, new Loaded(list, parsed, fetched), false);
            } catch (final RuntimeException exception) {
                final var message = describe(exception);
                log.warn("Blocklist source {} could not be loaded ({}): {}", source.getName(), attempt.label(), message);
                log.debug("Blocklist source {} download failed ({})", source.getName(), attempt.label(), exception);
                errors.add(attempts.size() == 1 ? message : attempt.label() + ": " + message);
            }
        }
        return new Download(errors, null, false);
    }

    private BlocklistSource record(final BlocklistSource source, final Download download) {
        final var errors = new ArrayList<>(download.errors());
        final var previousError = source.getLastError();
        final var loaded = download.loaded();
        final var refusal = loaded == null ? Optional.<String>empty() : store(source, loaded);
        refusal.ifPresent(errors::add);
        final var stored = loaded != null && refusal.isEmpty();
        final var lastError = errors.isEmpty() ? null : String.join(ERROR_SEPARATOR, errors);
        final var fetchedAt = LocalDateTime.now();
        final var recorded = stored
                ? blocklistSourceRepository.recordLoadedList(source.getId(), source.getKind(), fetchedAt, lastError,
                        loaded.attempt().url(), loaded.list().entries().size(), loaded.list().version(),
                        fitting(loaded.fetched().etag(), MAX_ETAG_LENGTH), fitting(loaded.fetched().lastModified(), MAX_LAST_MODIFIED_LENGTH))
                : download.unchanged()
                        ? blocklistSourceRepository.recordUnchangedList(source.getId(), source.getKind(), fetchedAt, lastError)
                        : blocklistSourceRepository.recordRefresh(source.getId(), source.getKind(), fetchedAt, lastError);
        final var current = blocklistSourceRepository.findById(source.getId()).orElse(source);
        if (recorded == 0) {
            log.info("Blocklist source {} was disabled, deleted or changed kind while it was stored, discarding the download",
                    current.getName());
            if (stored) {
                removeStoredSet(current);
            }
        } else {
            recordHealth(current.getId(), stored || download.unchanged());
            alertWhenHealthChanged(current.getName(), previousError, lastError, stored);
        }
        return current;
    }

    private void removeStoredSet(final BlocklistSource source) {
        try {
            blocklistStore.remove(source.getId());
        } catch (final RuntimeException exception) {
            log.warn("Could not remove the Redis set of blocklist source {} exception={}", source.getName(), exception.getClass().getName());
        }
    }

    private void recordHealth(final long sourceId, final boolean refreshed) {
        if (refreshed) {
            blocklistHealth.refreshed(sourceId);
        } else {
            blocklistHealth.failed(sourceId);
        }
    }

    private void alertWhenHealthChanged(final String name, final String previousError, final String lastError, final boolean stored) {
        if (previousError == null && lastError != null) {
            eventPublisher.publishEvent(new AdminAlertEvent.BlocklistSourceFailed(name, lastError, stored));
        } else if (previousError != null && lastError == null) {
            eventPublisher.publishEvent(new AdminAlertEvent.BlocklistSourceRecovered(name));
        }
    }

    private Optional<String> store(final BlocklistSource source, final Loaded loaded) {
        final var entries = loaded.list().entries();
        try {
            final var refusal = blocklistMemoryGuard.refusal(source, entries.size());
            if (refusal.isPresent()) {
                log.warn("Blocklist source {} was not loaded: {}", source.getName(), refusal.get());
                return refusal;
            }
            blocklistStore.replace(source.getId(), entries);
        } catch (final RuntimeException exception) {
            log.warn("Blocklist source {} could not be stored in Redis", source.getName(), exception);
            return Optional.of("could not store the list in Redis");
        }
        log.info("Blocklist source {} loaded {} entries ({})", source.getName(), entries.size(), loaded.attempt().label());
        return Optional.empty();
    }

    private void refuseShrunkenList(final BlocklistSource source, final ParsedBlocklist list, final RefreshTrigger trigger, final int limit) {
        final var loadedBefore = source.getDomainCount();
        if (trigger != RefreshTrigger.SCHEDULED || list.declaredEntries() != null || loadedBefore == null || loadedBefore < MIN_GUARDED_ENTRIES
                || list.entries().size() >= Math.min(loadedBefore, limit) * MIN_KEPT_SHARE || !holdsASet(source)) {
            return;
        }
        throw new BlocklistFormatException(String.format(Locale.ROOT, SHRUNK, list.entries().size(), loadedBefore));
    }

    private boolean holdsASet(final BlocklistSource source) {
        try {
            return blocklistStore.exists(source.getId());
        } catch (final DataAccessException exception) {
            log.warn("Could not check the Redis set of blocklist source {}, keeping it exception={}",
                    source.getName(), exception.getClass().getName());
            return true;
        }
    }

    private boolean sendsValidators(final BlocklistSource source, final String url) {
        if (!url.equals(source.getLastSuccessUrl()) || StringUtils.isAllBlank(source.getEtag(), source.getLastModified())) {
            return false;
        }
        try {
            return blocklistStore.exists(source.getId());
        } catch (final DataAccessException exception) {
            log.warn("Could not check the Redis set of blocklist source {}, downloading it in full exception={}",
                    source.getName(), exception.getClass().getName());
            return false;
        }
    }

    private int entryLimitOf(final SourceKind kind) {
        return switch (kind) {
            case TOP_SITES -> settingsService.number(SettingKey.SIGN_IN_FORM_TOP_SITES);
            case BRAND_SITES -> settingsService.number(SettingKey.BRAND_SITES_TOP_SITES);
            default -> MAX_ENTRIES;
        };
    }

    private String topSitesUrl(final String apiUrl, final int sites) {
        final var download = downloadLinkOf(publicHttpFetcher.fetch(apiUrl, MAX_API_ANSWER_BYTES, null, null, REQUEST_TIMEOUT).body());
        final var listSize = LIST_SIZE.matcher(download);
        if (!listSize.find()) {
            throw new BlocklistFormatException(NO_LIST_SIZE);
        }
        return listSize.replaceFirst("/" + sites);
    }

    private static String downloadLinkOf(final Path answer) {
        try {
            return JSON.readTree(Files.readString(answer)).path("download").stringValueOpt()
                    .filter(StringUtils::isNotBlank)
                    .orElseThrow(() -> new BlocklistFormatException(NO_DOWNLOAD_LINK));
        } catch (final JacksonException exception) {
            throw new BlocklistFormatException(NO_DOWNLOAD_LINK);
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        } finally {
            delete(answer);
        }
    }

    private ParsedBlocklist parse(final SourceKind kind, final Path body, final int limit) {
        try (final var reader = new BufferedReader(new InputStreamReader(Files.newInputStream(body), StandardCharsets.UTF_8))) {
            return switch (kind) {
                case DOMAINS, SHORTENERS -> blocklistParser.parse(reader, limit);
                case LINKS -> linkListParser.parse(reader, limit);
                case TOP_SITES -> topSitesParser.parse(reader, limit);
                case ENDINGS -> endingsParser.parse(reader, limit);
                case BRAND_SITES -> brandSitesParser.parse(reader, limit);
            };
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        } finally {
            delete(body);
        }
    }

    private static void delete(final Path body) {
        try {
            Files.deleteIfExists(body);
        } catch (final IOException exception) {
            log.warn("Could not delete the downloaded blocklist {}", body, exception);
        }
    }

    private static String fitting(final String validator, final int maxLength) {
        return StringUtils.length(validator) <= maxLength ? validator : null;
    }

    private static List<Attempt> attemptsOf(final BlocklistSource source) {
        final var fallbackUrls = Objects.requireNonNullElse(source.getFallbackUrls(), List.<String>of());
        final var attempts = new ArrayList<Attempt>();
        attempts.add(new Attempt("main", source.getUrl()));
        for (int index = 0; index < fallbackUrls.size(); index++) {
            attempts.add(new Attempt("fallback " + (index + 1), fallbackUrls.get(index)));
        }
        return attempts;
    }

    private static String describe(final RuntimeException exception) {
        if (exception instanceof CommonException && exception.getMessage() != null) {
            return exception.getMessage();
        }
        if (exception instanceof UncheckedIOException) {
            return "could not read the download";
        }
        return "unexpected error";
    }

    private record Attempt(String label, String url) {}

    private record Loaded(Attempt attempt, ParsedBlocklist list, FetchResult fetched) {}

    private record Download(List<String> errors, Loaded loaded, boolean unchanged) {}

}
