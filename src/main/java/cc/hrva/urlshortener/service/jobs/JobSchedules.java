package cc.hrva.urlshortener.service.jobs;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

@Component
@RequiredArgsConstructor
public class JobSchedules {

    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern EVERY_N = Pattern.compile("(?:\\*|0)/(\\d+)");
    private static final String ANY = "*";
    private static final String NO_SPECIFIC_VALUE = "?";
    private static final int CRON_FIELDS = 6;
    private static final long SECONDS_PER_MINUTE = 60;
    private static final long SECONDS_PER_HOUR = 3600;
    private static final long HALF_A_SECOND_MILLIS = 500;

    private final ObjectProvider<ScheduledTaskHolder> taskHolders;

    public Map<String, Schedule> all() {
        final var schedules = new HashMap<String, Schedule>();
        taskHolders.orderedStream().flatMap(holder -> holder.getScheduledTasks().stream()).forEach(task -> {
            final var name = nameOf(task);
            if (name != null) {
                schedules.putIfAbsent(name, new Schedule(textOf(task), nextRunAt(task)));
            }
        });
        return schedules;
    }

    static String describeCron(final String expression) {
        final var fields = StringUtils.split(expression);
        if (fields == null || fields.length != CRON_FIELDS) {
            return null;
        }
        final var minute = fields[1];
        final var hour = fields[2];
        final var dayOfMonth = fields[3];
        final var dayOfWeek = fields[5];
        if (!isAny(dayOfMonth) || !ANY.equals(fields[4])) {
            return null;
        }
        if (!isAny(dayOfWeek)) {
            return NUMBER.matcher(hour).matches() ? "Weekly" : null;
        }
        if (NUMBER.matcher(hour).matches()) {
            return NUMBER.matcher(minute).matches() ? "Daily" : null;
        }
        if (!ANY.equals(hour)) {
            return null;
        }
        if (NUMBER.matcher(minute).matches()) {
            return "Every hour";
        }
        final var everyN = EVERY_N.matcher(minute);
        if (everyN.matches()) {
            return everyMinutes(Long.parseLong(everyN.group(1)));
        }
        return null;
    }

    static String describeInterval(final Duration interval) {
        final var seconds = interval.toSeconds();
        if (seconds <= 0) {
            return null;
        }
        if (seconds % SECONDS_PER_HOUR == 0) {
            final var hours = seconds / SECONDS_PER_HOUR;
            return hours == 1 ? "Every hour" : "Every " + hours + " hours";
        }
        if (seconds % SECONDS_PER_MINUTE == 0) {
            return everyMinutes(seconds / SECONDS_PER_MINUTE);
        }
        return "Every " + seconds + " seconds";
    }

    private static String everyMinutes(final long minutes) {
        return minutes == 1 ? "Every minute" : "Every " + minutes + " minutes";
    }

    private static boolean isAny(final String field) {
        return ANY.equals(field) || NO_SPECIFIC_VALUE.equals(field);
    }

    private static String nameOf(final ScheduledTask task) {
        if (task.getTask().getRunnable() instanceof ScheduledMethodRunnable runnable) {
            return JobNames.of(runnable.getMethod());
        }
        final var target = task.getTask().toString();
        final var methodName = StringUtils.substringAfterLast(target, ".");
        final var className = StringUtils.substringBeforeLast(target, ".");
        if (methodName.isEmpty() || className.isEmpty() || !ClassUtils.isPresent(className, JobSchedules.class.getClassLoader())) {
            return null;
        }
        return JobNames.of(ClassUtils.resolveClassName(className, JobSchedules.class.getClassLoader()), methodName);
    }

    private static String textOf(final ScheduledTask task) {
        return switch (task.getTask()) {
            case CronTask cron -> describeCron(cron.getExpression());
            case IntervalTask interval -> describeInterval(interval.getIntervalDuration());
            default -> null;
        };
    }

    private static LocalDateTime nextRunAt(final ScheduledTask task) {
        final var next = task.nextExecution();
        return next == null ? null : LocalDateTime.ofInstant(next.plusMillis(HALF_A_SECOND_MILLIS).truncatedTo(ChronoUnit.SECONDS), ZoneOffset.UTC);
    }

    public record Schedule(String text, LocalDateTime nextRunAt) {
    }

}
