package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import cc.hrva.urlshortener.service.LoginAttemptService;
import org.springframework.stereotype.Service;

@Service
public class DefaultLoginAttemptService implements LoginAttemptService {

    private static final long MAX_TRACKED_KEYS = 10_000;

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
    public void clearLoginAttempts() {
        attemptsCache.invalidateAll();
    }

}
