package cc.hrva.urlshortener.service.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class ScheduledJobAspect {

    private final JobRuns jobRuns;

    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled) && within(cc.hrva.urlshortener..*)")
    public Object track(final ProceedingJoinPoint joinPoint) throws Throwable {
        final var name = JobNames.of(((MethodSignature) joinPoint.getSignature()).getMethod());
        jobRuns.started(name);
        try {
            final var result = joinPoint.proceed();
            jobRuns.finished(name);
            return result;
        } catch (final JobFailedException failed) {
            jobRuns.failed(name, failed);
            log.warn("Scheduled job failed job={} reason={}", name, failed.getMessage());
            return null;
        } catch (final Throwable throwable) {
            jobRuns.failed(name, throwable);
            throw throwable;
        }
    }

}
