package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.stats.PublicStatsResponse;
import cc.hrva.urlshortener.service.PublicStatsService;
import cc.hrva.urlshortener.service.stats.PublicStats;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultPublicStatsService implements PublicStatsService {

    private static final String KEY = "public:stats";
    private static final Duration TIME_TO_LIVE = Duration.ofHours(1);

    private final PublicStats publicStats;
    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;

    @Override
    public PublicStatsResponse stats() {
        return cached().orElseGet(this::computed);
    }

    private Optional<PublicStatsResponse> cached() {
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(KEY))
                    .map(json -> jsonMapper.readValue(json, PublicStatsResponse.class));
        } catch (final RuntimeException exception) {
            log.warn("Public stats cache read failed, computing them exception={}", exception.getClass().getName());
            return Optional.empty();
        }
    }

    private PublicStatsResponse computed() {
        final var stats = publicStats.collect();
        try {
            redisTemplate.opsForValue().set(KEY, jsonMapper.writeValueAsString(stats), TIME_TO_LIVE);
        } catch (final RuntimeException exception) {
            log.warn("Public stats cache write failed exception={}", exception.getClass().getName());
        }
        return stats;
    }

}
