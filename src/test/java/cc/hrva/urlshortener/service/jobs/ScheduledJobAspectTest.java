package cc.hrva.urlshortener.service.jobs;

import cc.hrva.urlshortener.service.notification.MutableClock;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScheduledJobAspectTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 3, 22, 31);

    private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfiguration.class);
    private final SampleJobs jobs = context.getBean(SampleJobs.class);
    private final JobRuns jobRuns = context.getBean(JobRuns.class);

    @AfterEach
    void close() {
        context.close();
    }

    @Test
    void wrapsEveryOtherAdviceSoAFailedCommitCountsAsFailed() {
        assertThat(ScheduledJobAspect.class.getAnnotation(Order.class).value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void anOkRunIsRecorded() {
        jobs.cleanUp();

        assertThat(run("cleanUp")).isEqualTo(new JobRun("cleanUp", "cleanUp", NOW, NOW, JobOutcome.OK, null));
        assertThat(jobs.runCount()).isEqualTo(1);
    }

    @Test
    void aFailedRunIsRecordedAndTheFailureStillReachesTheScheduler() {
        assertThatThrownBy(jobs::failToSend).isInstanceOf(IllegalStateException.class).hasMessage("mail server down");

        assertThat(run("failToSend")).isEqualTo(new JobRun("failToSend", "failToSend", NOW, NOW, JobOutcome.FAILED,
                "java.lang.IllegalStateException: mail server down"));
    }

    @Test
    void aCommitThatFailsAfterTheMethodReturnedCountsAsFailed() {
        assertThatThrownBy(jobs::writeInATransaction).isInstanceOf(IllegalStateException.class).hasMessage("commit refused");

        assertThat(jobs.runCount()).isEqualTo(1);
        assertThat(run("writeInATransaction").lastOutcome()).isEqualTo(JobOutcome.FAILED);
        assertThat(run("writeInATransaction").lastError()).isEqualTo("java.lang.IllegalStateException: commit refused");
    }

    @Test
    void aMethodThatIsNotScheduledIsNotTracked() {
        jobs.notAJob();

        assertThat(jobRuns.all()).extracting(JobRun::name).doesNotContain("notAJob");
    }

    private JobRun run(final String name) {
        return jobRuns.all().stream().filter(run -> run.name().equals(name)).findFirst().orElseThrow();
    }

    @Configuration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @EnableTransactionManagement
    @Import({ScheduledJobAspect.class, JobRuns.class})
    static class TestConfiguration {

        @Bean
        Clock clock() {
            return new MutableClock(Instant.parse("2026-11-03T22:31:00Z"));
        }

        @Bean
        SampleJobs sampleJobs() {
            return new SampleJobs();
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new RefusingTransactionManager();
        }

    }

    static class SampleJobs {

        private int runs;

        public int runCount() {
            return runs;
        }

        @Scheduled(fixedDelay = 60_000)
        public void cleanUp() {
            runs++;
        }

        @Scheduled(cron = "0 0 * * * *")
        public void failToSend() {
            throw new IllegalStateException("mail server down");
        }

        @Transactional
        @Scheduled(cron = "0 30 3 * * *")
        public void writeInATransaction() {
            runs++;
        }

        public void notAJob() {
            runs++;
        }

    }

    static class RefusingTransactionManager implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(final TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(final TransactionStatus status) {
            throw new IllegalStateException("commit refused");
        }

        @Override
        public void rollback(final TransactionStatus status) {
        }

    }

}
