package cc.hrva.urlshortener.configuration;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.DefaultLifecycleProcessor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsyncExecutorConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskExecutionAutoConfiguration.class, TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(AsyncExecutorConfiguration.class, AsyncProbeConfiguration.class);

    @Test
    void redirectChecksRunOnTheirOwnPoolWhileEveryOtherBackgroundTaskKeepsTheApplicationExecutor() {
        contextRunner.run(context -> {
            final var probe = context.getBean(AsyncProbe.class);

            assertThat(probe.unqualified().get(5, TimeUnit.SECONDS)).startsWith("task-");
            assertThat(probe.redirectCheck().get(5, TimeUnit.SECONDS)).startsWith("redirect-check-");
            assertThat(context.getBean("taskExecutor")).isSameAs(context.getBean("applicationTaskExecutor"));
        });
    }

    @Test
    void theApplicationExecutorStillFollowsTheTaskExecutionProperties() {
        contextRunner.withPropertyValues("spring.task.execution.pool.core-size=3").run(context ->
                assertThat(context.getBean("applicationTaskExecutor", ThreadPoolTaskExecutor.class).getCorePoolSize()).isEqualTo(3));
    }

    @Test
    void theRedirectCheckPoolHasTwoThreadsAndQueuesTwoHundredChecks() {
        contextRunner.run(context -> {
            final var executor = context.getBean(AsyncExecutorConfiguration.REDIRECT_CHECK_EXECUTOR, ThreadPoolTaskExecutor.class);

            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(2);
            assertThat(executor.getQueueCapacity()).isEqualTo(200);
        });
    }

    @Test
    void aFullRedirectCheckQueueRefusesTheNextCheck() {
        contextRunner.run(context -> {
            final var executor = context.getBean(AsyncExecutorConfiguration.REDIRECT_CHECK_EXECUTOR, ThreadPoolTaskExecutor.class);
            final var release = new CountDownLatch(1);
            try {
                IntStream.range(0, 202).forEach(task -> executor.execute(() -> awaitQuietly(release)));

                assertThatThrownBy(() -> executor.execute(() -> {})).isInstanceOf(TaskRejectedException.class);
            } finally {
                release.countDown();
            }
        });
    }

    @Test
    void aShutdownNeverInterruptsARunningRedirectCheckEvenPastTheLifecycleTimeout() {
        final var finished = new AtomicBoolean();
        final var interrupted = new AtomicBoolean();

        contextRunner.withBean("lifecycleProcessor", DefaultLifecycleProcessor.class, AsyncExecutorConfigurationTest::impatientLifecycle)
                .run(context -> context.getBean(AsyncExecutorConfiguration.REDIRECT_CHECK_EXECUTOR, ThreadPoolTaskExecutor.class)
                        .execute(() -> {
                            try {
                                Thread.sleep(500);
                                finished.set(true);
                            } catch (final InterruptedException exception) {
                                interrupted.set(true);
                            }
                        }));

        assertThat(interrupted).isFalse();
        assertThat(finished).isTrue();
    }

    private static DefaultLifecycleProcessor impatientLifecycle() {
        final var lifecycleProcessor = new DefaultLifecycleProcessor();
        lifecycleProcessor.setTimeoutPerShutdownPhase(100);
        return lifecycleProcessor;
    }

    private static void awaitQuietly(final CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    static class AsyncProbeConfiguration {

        @Bean
        AsyncProbe asyncProbe() {
            return new AsyncProbe();
        }

    }

    static class AsyncProbe {

        @Async
        public CompletableFuture<String> unqualified() {
            return CompletableFuture.completedFuture(Thread.currentThread().getName());
        }

        @Async(AsyncExecutorConfiguration.REDIRECT_CHECK_EXECUTOR)
        public CompletableFuture<String> redirectCheck() {
            return CompletableFuture.completedFuture(Thread.currentThread().getName());
        }

    }

}
