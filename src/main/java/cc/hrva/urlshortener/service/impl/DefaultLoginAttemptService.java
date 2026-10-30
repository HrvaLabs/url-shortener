package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.dto.LoginAttemptResponse;
import cc.hrva.urlshortener.util.InMemoryPages;
import cc.hrva.urlshortener.util.SortWhitelist;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import cc.hrva.urlshortener.service.LoginAttemptService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class DefaultLoginAttemptService implements LoginAttemptService {

    private static final long MAX_TRACKED_KEYS = 10_000;
    private static final String ATTEMPTS = "attempts";
    private static final String KEY = "key";
    private static final SortWhitelist ATTEMPT_SORTS = SortWhitelist.of(ATTEMPTS, KEY)
            .byDefault(Sort.by(Sort.Order.desc(ATTEMPTS)))
            .thenBy(Sort.Order.asc(KEY));

    private final AppProperties appProperties;
    private final LoadingCache<String, Integer> attemptsCache;

    public DefaultLoginAttemptService(final AppProperties appProperties) {
        super();
        this.appProperties = appProperties;
        attemptsCache = CacheBuilder.newBuilder().maximumSize(MAX_TRACKED_KEYS).expireAfterWrite(1, TimeUnit.DAYS).build(new CacheLoader<>() {
                    public Integer load(final String key) {
                        return 0;
                    }
                });
    }

    @Override
    public void loginSucceeded(final String key) {
        attemptsCache.invalidate(key);
    }

    @Override
    public void loginFailed(final String key) {
        int attempts;
        try {
            attempts = attemptsCache.get(key);
        } catch (final ExecutionException e) {
            attempts = 0;
        }
        attempts++;
        attemptsCache.put(key, attempts);
    }

    @Override
    public boolean isBlocked(final String key) {
        try {
            return attemptsCache.get(key) >= appProperties.getMaxLoginAttempts();
        } catch (final ExecutionException e) {
            return false;
        }
    }

    @Override
    public java.util.Map<String, Integer> getLoginAttempts() {
        final var map = new java.util.LinkedHashMap<String, Integer>();
        map.putAll(attemptsCache.asMap());
        return map;
    }

    @Override
    public Page<LoginAttemptResponse> attempts(final String search, final Pageable pageable) {
        final var order = ATTEMPT_SORTS.sortOf(pageable.getSort()).stream()
                .map(DefaultLoginAttemptService::comparatorOf)
                .reduce(Comparator::thenComparing)
                .orElseThrow();
        final var term = StringUtils.isBlank(search) ? null : search.strip().toLowerCase(Locale.ROOT);
        final var rows = attemptsCache.asMap().entrySet().stream()
                .filter(entry -> term == null || entry.getKey().toLowerCase(Locale.ROOT).contains(term))
                .map(entry -> new LoginAttemptResponse(entry.getKey(), entry.getValue()))
                .sorted(order)
                .toList();
        return InMemoryPages.of(rows, pageable);
    }

    private static Comparator<LoginAttemptResponse> comparatorOf(final Sort.Order order) {
        final Comparator<LoginAttemptResponse> ascending = ATTEMPTS.equals(order.getProperty())
                ? Comparator.comparingInt(LoginAttemptResponse::attempts)
                : Comparator.comparing(LoginAttemptResponse::key);
        return order.isAscending() ? ascending : ascending.reversed();
    }

    @Override
    public void clearLoginAttempts() {
        attemptsCache.invalidateAll();
    }

}
