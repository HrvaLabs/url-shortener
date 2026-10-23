package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.client.DiscordWebhookClient;
import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.service.DiscordAlertService;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.service.alert.DiscordAlertFormatter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultDiscordAlertService implements DiscordAlertService {

    private static final int MAX_BUFFERED_ALERTS = 200;
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final AppProperties appProperties;
    private final DiscordAlertFormatter discordAlertFormatter;
    private final DiscordWebhookClient discordWebhookClient;
    private final BlockingQueue<AdminAlertEvent> buffer = new LinkedBlockingQueue<>(MAX_BUFFERED_ALERTS);
    private final AtomicInteger dropped = new AtomicInteger();

    @Override
    @TransactionalEventListener(fallbackExecution = true)
    public void onAdminAlert(final AdminAlertEvent alert) {
        if (isOff()) {
            return;
        }
        if (!buffer.offer(alert)) {
            dropped.incrementAndGet();
        }
    }

    @Override
    @Scheduled(fixedDelay = 30_000)
    public void flush() {
        flush(true);
    }

    @PostConstruct
    public void logWhenOff() {
        if (isOff()) {
            log.info("Discord alerts are off: no DISCORD_WEBHOOK_URL");
        }
    }

    @PreDestroy
    public void flushBeforeShutdown() {
        flush(false);
    }

    private void flush(final boolean mayWaitOutRateLimit) {
        if (isOff()) {
            return;
        }
        final var alerts = new ArrayList<AdminAlertEvent>();
        buffer.drainTo(alerts);
        final var droppedSinceLastFlush = dropped.getAndSet(0);
        if (alerts.isEmpty() && droppedSinceLastFlush == 0) {
            return;
        }
        try {
            final var webhookUrl = StringUtils.trim(appProperties.getDiscordWebhookUrl());
            final var message = discordAlertFormatter.message(alerts, droppedSinceLastFlush);
            final var sent = mayWaitOutRateLimit
                    ? discordWebhookClient.send(webhookUrl, message, TIMEOUT)
                    : discordWebhookClient.sendWithoutWaiting(webhookUrl, message, TIMEOUT);
            if (sent) {
                log.info("Sent admin alerts to Discord alerts={} dropped={}", alerts.size(), droppedSinceLastFlush);
            } else if (Thread.currentThread().isInterrupted()) {
                keepForTheNextFlush(alerts, droppedSinceLastFlush);
            }
        } catch (final RuntimeException exception) {
            log.warn("Discord alerts dropped exception={}", exception.getClass().getName());
        }
    }

    private void keepForTheNextFlush(final List<AdminAlertEvent> alerts, final int droppedSinceLastFlush) {
        final var newer = new ArrayList<AdminAlertEvent>();
        buffer.drainTo(newer);
        dropped.addAndGet(droppedSinceLastFlush);
        Stream.concat(alerts.stream(), newer.stream()).forEach(alert -> {
            if (!buffer.offer(alert)) {
                dropped.incrementAndGet();
            }
        });
        log.info("Discord alerts were interrupted, keeping them for the next flush alerts={}", alerts.size());
    }

    private boolean isOff() {
        return StringUtils.isBlank(appProperties.getDiscordWebhookUrl());
    }

}
