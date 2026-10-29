package cc.hrva.urlshortener.configuration;

import java.time.Duration;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncExecutorConfiguration {

    public static final String REDIRECT_CHECK_EXECUTOR = "redirectCheckExecutor";
    public static final String NOTIFICATION_TASK_EXECUTOR = "notificationTaskExecutor";
    private static final int REDIRECT_CHECK_THREADS = 2;
    private static final int REDIRECT_CHECK_QUEUE_CAPACITY = 200;
    private static final String REDIRECT_CHECK_THREAD_PREFIX = "redirect-check-";
    private static final Duration REDIRECT_CHECK_SHUTDOWN_GRACE = Duration.ofSeconds(15);
    private static final int NOTIFICATION_TASK_THREADS = 4;
    private static final int NOTIFICATION_TASK_QUEUE_CAPACITY = 16;
    private static final String NOTIFICATION_TASK_THREAD_PREFIX = "notification-task-";

    @Bean(name = {TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME, AsyncAnnotationBeanPostProcessor.DEFAULT_TASK_EXECUTOR_BEAN_NAME})
    public ThreadPoolTaskExecutor applicationTaskExecutor(final ThreadPoolTaskExecutorBuilder threadPoolTaskExecutorBuilder) {
        return threadPoolTaskExecutorBuilder.build();
    }

    @Bean(REDIRECT_CHECK_EXECUTOR)
    public ThreadPoolTaskExecutor redirectCheckExecutor() {
        return new ThreadPoolTaskExecutorBuilder()
                .corePoolSize(REDIRECT_CHECK_THREADS)
                .maxPoolSize(REDIRECT_CHECK_THREADS)
                .queueCapacity(REDIRECT_CHECK_QUEUE_CAPACITY)
                .threadNamePrefix(REDIRECT_CHECK_THREAD_PREFIX)
                .awaitTermination(true)
                .awaitTerminationPeriod(REDIRECT_CHECK_SHUTDOWN_GRACE)
                .build();
    }

    @Bean(NOTIFICATION_TASK_EXECUTOR)
    public ThreadPoolTaskExecutor notificationTaskExecutor() {
        return new ThreadPoolTaskExecutorBuilder()
                .corePoolSize(NOTIFICATION_TASK_THREADS)
                .maxPoolSize(NOTIFICATION_TASK_THREADS)
                .queueCapacity(NOTIFICATION_TASK_QUEUE_CAPACITY)
                .threadNamePrefix(NOTIFICATION_TASK_THREAD_PREFIX)
                .build();
    }

}
