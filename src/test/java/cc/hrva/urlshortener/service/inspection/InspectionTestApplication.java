package cc.hrva.urlshortener.service.inspection;

import cc.hrva.urlshortener.audit.AuditSubjects;
import cc.hrva.urlshortener.client.BrowserRunClient;
import cc.hrva.urlshortener.client.VirusTotalClient;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.repository.LinkInspectionRepository;
import cc.hrva.urlshortener.repository.LinkScreenshotRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.mcp.bridge.InMemoryRedis;
import cc.hrva.urlshortener.service.AuditLogService;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.notification.MutableClock;
import cc.hrva.urlshortener.service.ratelimit.RedisFixedWindowRateLimiter;
import cc.hrva.urlshortener.service.settings.SettingsServiceFixtures;
import java.time.Instant;
import org.mockito.Mockito;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackageClasses = Url.class)
@EnableJpaRepositories(
        basePackageClasses = UrlRepository.class,
        includeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {UrlRepository.class, LinkInspectionRepository.class, LinkScreenshotRepository.class}))
@Import({DefaultLinkInspectionService.class, InspectionWorker.class, RedisFixedWindowRateLimiter.class})
class InspectionTestApplication {

    static final String DATABASE_URL = "spring.datasource.url=jdbc:h2:mem:link-inspection;DB_CLOSE_DELAY=-1;NON_KEYWORDS=KEY,VALUE";

    @Bean
    AppProperties appProperties() {
        return new AppProperties();
    }

    @Bean
    SettingsService settingsService(final AppProperties appProperties) {
        return SettingsServiceFixtures.defaults(appProperties);
    }

    @Bean
    MutableClock clock() {
        return new MutableClock(Instant.parse("2026-11-03T10:00:00Z"));
    }

    @Bean
    BrowserRunClient browserRunClient() {
        return Mockito.mock(BrowserRunClient.class);
    }

    @Bean
    VirusTotalClient virusTotalClient() {
        return Mockito.mock(VirusTotalClient.class);
    }

    @Bean
    InMemoryRedis inMemoryRedis() {
        return new InMemoryRedis();
    }

    @Bean
    StringRedisTemplate stringRedisTemplate(final InMemoryRedis inMemoryRedis) {
        final var redisTemplate = Mockito.mock(StringRedisTemplate.class);
        inMemoryRedis.backing(redisTemplate);
        return redisTemplate;
    }

    @Bean
    AuditLogService auditLogService() {
        return Mockito.mock(AuditLogService.class);
    }

    @Bean
    AuditSubjects auditSubjects() {
        return Mockito.mock(AuditSubjects.class);
    }

}
