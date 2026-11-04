package cc.hrva.urlshortener.service.jobs;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class ScheduledJobAspect {

    private final JobRuns jobRuns;

    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled) && within(cc.hrva.urlshortener..*)")
    public Object track(final ProceedingJoinPoint joinPoint) throws Throwable {
        final var name = joinPoint.getSignature().getName();
        jobRuns.started(name);
        try {
            final var result = joinPoint.proceed();
            jobRuns.finished(name);
            return result;
        } catch (final Throwable throwable) {
            jobRuns.failed(name, throwable);
            throw throwable;
        }
    }

}
