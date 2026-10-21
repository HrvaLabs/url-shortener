package cc.hrva.urlshortener.configuration;

import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.task.ThreadPoolTaskExecutorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncExecutorConfiguration {

    public static final String REDIRECT_CHECK_EXECUTOR = "redirectCheckExecutor";
    private static final int REDIRECT_CHECK_THREADS = 2;
    private static final int REDIRECT_CHECK_QUEUE_CAPACITY = 200;
    private static final String REDIRECT_CHECK_THREAD_PREFIX = "redirect-check-";

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
                .build();
    }

}
