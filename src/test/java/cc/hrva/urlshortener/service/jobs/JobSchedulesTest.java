package cc.hrva.urlshortener.service.jobs;

import cc.hrva.urlshortener.UrlShortenerApplication;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.util.ClassUtils;

import static org.assertj.core.api.Assertions.assertThat;

class JobSchedulesTest {

    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

    @AfterEach
    void stop() {
        registrar.destroy();
        scheduler.shutdown();
    }

    @Test
    void cronJobsReadAsHowOftenTheyRun() {
        assertThat(JobSchedules.describeCron("0 */1 * * * *")).isEqualTo("Every minute");
        assertThat(JobSchedules.describeCron("30 */5 * * * *")).isEqualTo("Every 5 minutes");
        assertThat(JobSchedules.describeCron("0 0 * * * *")).isEqualTo("Every hour");
        assertThat(JobSchedules.describeCron("0 30 3 * * *")).isEqualTo("Daily");
        assertThat(JobSchedules.describeCron("0 0 9 * * MON")).isEqualTo("Weekly");
        assertThat(JobSchedules.describeCron("0 0 9 1 * *")).isNull();
    }

    @Test
    void intervalJobsReadAsHowOftenTheyRun() {
        assertThat(JobSchedules.describeInterval(Duration.ofSeconds(15))).isEqualTo("Every 15 seconds");
        assertThat(JobSchedules.describeInterval(Duration.ofSeconds(30))).isEqualTo("Every 30 seconds");
        assertThat(JobSchedules.describeInterval(Duration.ofMinutes(1))).isEqualTo("Every minute");
        assertThat(JobSchedules.describeInterval(Duration.ofMinutes(5))).isEqualTo("Every 5 minutes");
        assertThat(JobSchedules.describeInterval(Duration.ofHours(1))).isEqualTo("Every hour");
        assertThat(JobSchedules.describeInterval(Duration.ofSeconds(90))).isEqualTo("Every 90 seconds");
    }

    @Test
    void everyScheduleInTheAppHasAText() {
        assertThat(scheduledAnnotations()).allSatisfy(scheduled -> {
            if (!scheduled.cron().isEmpty()) {
                assertThat(JobSchedules.describeCron(scheduled.cron())).as(scheduled.cron()).isNotNull();
            } else if (scheduled.fixedDelay() > 0) {
                assertThat(JobSchedules.describeInterval(Duration.ofMillis(scheduled.fixedDelay()))).isNotNull();
            } else {
                assertThat(scheduled.fixedDelayString()).isEqualTo("${app.inspection.interval:15s}");
            }
        });
    }

    @Test
    void theRegisteredTasksGiveEachJobItsScheduleAndNextRun() throws NoSuchMethodException {
        scheduler.initialize();
        registrar.setTaskScheduler(scheduler);
        final var jobs = new SampleJobs();
        registrar.addCronTask(new CronTask(new ScheduledMethodRunnable(jobs, SampleJobs.class.getMethod("nightly")), "0 30 3 * * *"));
        registrar.addFixedDelayTask(new FixedDelayTask(new ScheduledMethodRunnable(jobs, SampleJobs.class.getMethod("often")), Duration.ofMinutes(5),
                Duration.ofMinutes(5)));
        registrar.afterPropertiesSet();
        final var beans = new StaticListableBeanFactory();
        beans.addBean("registrar", registrar);

        final var schedules = new JobSchedules(beans.getBeanProvider(org.springframework.scheduling.config.ScheduledTaskHolder.class)).all();

        assertThat(schedules).containsOnlyKeys("SampleJobs.nightly", "SampleJobs.often");
        assertThat(schedules.get("SampleJobs.nightly").text()).isEqualTo("Daily");
        assertThat(schedules.get("SampleJobs.nightly").nextRunAt()).isAfter(LocalDateTime.now(ZoneOffset.UTC))
                .isBefore(LocalDateTime.now(ZoneOffset.UTC).plusDays(1).plusMinutes(1));
        assertThat(schedules.get("SampleJobs.nightly").nextRunAt().getMinute()).isEqualTo(30);
        assertThat(schedules.get("SampleJobs.often").text()).isEqualTo("Every 5 minutes");
        assertThat(schedules.get("SampleJobs.often").nextRunAt()).isBetween(LocalDateTime.ofInstant(Instant.now().plusSeconds(200), ZoneOffset.UTC),
                LocalDateTime.ofInstant(Instant.now().plusSeconds(301), ZoneOffset.UTC));
    }

    @Test
    void withoutASchedulerThereAreNoSchedules() {
        assertThat(new JobSchedules(new StaticListableBeanFactory().getBeanProvider(org.springframework.scheduling.config.ScheduledTaskHolder.class)).all())
                .isEmpty();
    }

    private static List<Scheduled> scheduledAnnotations() {
        final var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) -> reader.getAnnotationMetadata().hasAnnotatedMethods(Scheduled.class.getName()));
        return scanner.findCandidateComponents(UrlShortenerApplication.class.getPackageName()).stream()
                .filter(candidate -> !Objects.requireNonNull(candidate.getResourceDescription()).contains("test-classes"))
                .map(candidate -> ClassUtils.resolveClassName(Objects.requireNonNull(candidate.getBeanClassName()), null))
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
                .map(method -> method.getAnnotation(Scheduled.class))
                .filter(Objects::nonNull)
                .toList();
    }

    public static class SampleJobs {

        public void nightly() {
        }

        public void often() {
        }

    }

}
