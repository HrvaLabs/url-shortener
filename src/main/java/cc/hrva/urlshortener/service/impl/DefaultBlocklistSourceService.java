package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.BlocklistSourceDto;
import cc.hrva.urlshortener.dto.BlocklistSourceResponse;
import cc.hrva.urlshortener.exception.BlocklistSourceNotFoundException;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.model.BlocklistSource;
import cc.hrva.urlshortener.model.enums.SourceKind;
import cc.hrva.urlshortener.repository.BlocklistSourceRepository;
import cc.hrva.urlshortener.service.BlocklistSourceService;
import cc.hrva.urlshortener.service.blocklist.BlocklistConfigurationChangedEvent;
import cc.hrva.urlshortener.service.blocklist.BlocklistRefresher;
import cc.hrva.urlshortener.service.blocklist.BlocklistStore;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultBlocklistSourceService implements BlocklistSourceService {

    private static final int DEFAULT_REFRESH_INTERVAL_HOURS = 6;
    private static final int MAX_REFRESH_INTERVAL_HOURS = 168;
    private static final int MAX_FALLBACK_URLS = 3;
    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_URL_LENGTH = 1000;
    private static final Duration DUE_TOLERANCE = Duration.ofMinutes(5);

    private final BlocklistSourceRepository blocklistSourceRepository;
    private final BlocklistStore blocklistStore;
    private final BlocklistRefresher blocklistRefresher;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    public List<BlocklistSourceResponse> list() {
        return blocklistSourceRepository.findAll(Sort.by("id")).stream().map(this::responseOf).toList();
    }

    @Override
    @Transactional
    public BlocklistSourceResponse create(final BlocklistSourceDto blocklistSourceDto) {
        validate(blocklistSourceDto);
        final var source = blocklistSourceRepository.save(BlocklistSource.builder()
                .name(blocklistSourceDto.name().strip())
                .url(blocklistSourceDto.url().strip())
                .action(blocklistSourceDto.action())
                .kind(Objects.requireNonNullElse(blocklistSourceDto.kind(), SourceKind.DOMAINS))
                .enabled(Objects.requireNonNullElse(blocklistSourceDto.enabled(), true))
                .refreshIntervalHours(Objects.requireNonNullElse(blocklistSourceDto.refreshIntervalHours(), DEFAULT_REFRESH_INTERVAL_HOURS))
                .fallbackUrls(fallbackUrlsOf(blocklistSourceDto).orElse(List.of()))
                .build());
        eventPublisher.publishEvent(new BlocklistConfigurationChangedEvent());
        return BlocklistSourceResponse.from(source, null);
    }

    @Override
    @Transactional
    public BlocklistSourceResponse update(final long id, final BlocklistSourceDto blocklistSourceDto) {
        final var source = find(id);
        validate(blocklistSourceDto);
        final var kindChanged = blocklistSourceDto.kind() != null && blocklistSourceDto.kind() != source.getKind();
        source.setName(blocklistSourceDto.name().strip());
        source.setUrl(blocklistSourceDto.url().strip());
        source.setAction(blocklistSourceDto.action());
        Optional.ofNullable(blocklistSourceDto.kind()).ifPresent(source::setKind);
        Optional.ofNullable(blocklistSourceDto.enabled()).ifPresent(source::setEnabled);
        Optional.ofNullable(blocklistSourceDto.refreshIntervalHours()).ifPresent(source::setRefreshIntervalHours);
        fallbackUrlsOf(blocklistSourceDto).ifPresent(source::setFallbackUrls);
        if (kindChanged) {
            forgetLoadedList(source);
        }
        if (kindChanged || !source.isEnabled()) {
            blocklistStore.remove(id);
        }
        final var saved = blocklistSourceRepository.save(source);
        eventPublisher.publishEvent(new BlocklistConfigurationChangedEvent());
        return responseOf(saved);
    }

    @Override
    @Transactional
    public void delete(final long id) {
        blocklistSourceRepository.delete(find(id));
        blocklistStore.remove(id);
        eventPublisher.publishEvent(new BlocklistConfigurationChangedEvent());
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public BlocklistSourceResponse refresh(final long id) {
        return responseOf(blocklistRefresher.refresh(find(id)));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void refreshAll() {
        refreshEnabled(source -> true);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void refreshDue() {
        refreshEnabled(this::isDue);
    }

    @Async
    @Override
    @EventListener(ApplicationReadyEvent.class)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void reloadMissing() {
        withoutSets(blocklistSourceRepository.findByEnabledTrue()).ifPresent(missing -> refreshEach(missing, source -> true));
    }

    private Optional<List<BlocklistSource>> withoutSets(final List<BlocklistSource> sources) {
        try {
            return Optional.of(sources.stream().filter(source -> !blocklistStore.exists(source.getId())).toList());
        } catch (final DataAccessException exception) {
            log.warn("Could not check which blocklist sets are missing, Redis is unavailable exception={}", exception.getClass().getName());
            return Optional.empty();
        }
    }

    private void refreshEnabled(final Predicate<BlocklistSource> selected) {
        refreshEach(blocklistSourceRepository.findByEnabledTrue(), selected);
    }

    private void refreshEach(final List<BlocklistSource> sources, final Predicate<BlocklistSource> selected) {
        for (final var source : sources) {
            try {
                if (selected.test(source)) {
                    blocklistRefresher.refresh(source);
                }
            } catch (final RuntimeException exception) {
                log.warn("Could not refresh blocklist source {}", source.getName(), exception);
            }
        }
    }

    private boolean isDue(final BlocklistSource source) {
        final var lastFetchedAt = source.getLastFetchedAt();
        final var dueAfter = LocalDateTime.now().minusHours(source.getRefreshIntervalHours()).plus(DUE_TOLERANCE);
        return lastFetchedAt == null || !lastFetchedAt.isAfter(dueAfter) || !blocklistStore.exists(source.getId());
    }

    private static void forgetLoadedList(final BlocklistSource source) {
        source.setDomainCount(null);
        source.setListVersion(null);
        source.setLastSuccessAt(null);
        source.setLastSuccessUrl(null);
        source.setEtag(null);
        source.setLastModified(null);
    }

    private BlocklistSource find(final long id) {
        return blocklistSourceRepository.findById(id)
                .orElseThrow(() -> new BlocklistSourceNotFoundException("Blocklist source not found"));
    }

    private BlocklistSourceResponse responseOf(final BlocklistSource source) {
        return BlocklistSourceResponse.from(source, blocklistStore.memoryBytes(source.getId()));
    }

    private static void validate(final BlocklistSourceDto blocklistSourceDto) {
        if (StringUtils.isBlank(blocklistSourceDto.name())) {
            throw new UrlValidationException("Enter a name for the list");
        }
        if (blocklistSourceDto.name().strip().length() > MAX_NAME_LENGTH) {
            throw new UrlValidationException("The name can have at most " + MAX_NAME_LENGTH + " characters");
        }
        validateListUrl(blocklistSourceDto.url(), "The list URL");
        if (blocklistSourceDto.action() == null) {
            throw new UrlValidationException("Choose whether the list blocks or flags links");
        }
        final var refreshIntervalHours = blocklistSourceDto.refreshIntervalHours();
        if (refreshIntervalHours != null && (refreshIntervalHours < 1 || refreshIntervalHours > MAX_REFRESH_INTERVAL_HOURS)) {
            throw new UrlValidationException("The refresh interval must be between 1 and " + MAX_REFRESH_INTERVAL_HOURS + " hours");
        }
        final var fallbackUrls = fallbackUrlsOf(blocklistSourceDto).orElse(List.of());
        if (fallbackUrls.size() > MAX_FALLBACK_URLS) {
            throw new UrlValidationException("A list can have at most " + MAX_FALLBACK_URLS + " fallback URLs");
        }
        for (int index = 0; index < fallbackUrls.size(); index++) {
            validateListUrl(fallbackUrls.get(index), "Fallback URL " + (index + 1));
        }
    }

    private static void validateListUrl(final String url, final String field) {
        if (StringUtils.isBlank(url) || !isHttpsAddress(url.strip())) {
            throw new UrlValidationException(field + " must be a valid https:// address");
        }
        if (url.strip().length() > MAX_URL_LENGTH) {
            throw new UrlValidationException(field + " can have at most " + MAX_URL_LENGTH + " characters");
        }
    }

    private static boolean isHttpsAddress(final String url) {
        try {
            final var uri = new URI(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && StringUtils.isNotBlank(uri.getHost());
        } catch (final URISyntaxException exception) {
            return false;
        }
    }

    private static Optional<List<String>> fallbackUrlsOf(final BlocklistSourceDto blocklistSourceDto) {
        return Optional.ofNullable(blocklistSourceDto.fallbackUrls())
                .map(urls -> urls.stream().filter(StringUtils::isNotBlank).map(String::strip).toList());
    }

}
