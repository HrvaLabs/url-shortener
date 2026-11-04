package cc.hrva.urlshortener.service.jobs;

import cc.hrva.urlshortener.UrlShortenerApplication;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.ClassUtils;

import static org.assertj.core.api.Assertions.assertThat;

class JobLabelsTest {

    @Test
    void everyScheduledMethodHasALabelAndEveryLabelAMethod() {
        assertThat(JobLabels.all().keySet()).containsExactlyInAnyOrderElementsOf(scheduledMethodNames());
    }

    @Test
    void noTwoScheduledMethodsShareAName() {
        final var names = scheduledMethodNames();

        assertThat(names).doesNotHaveDuplicates();
    }

    @Test
    void labelsAreReadableSentencesWithoutAFullStop() {
        assertThat(JobLabels.all().values()).allSatisfy(label -> {
            assertThat(label).matches("[A-Z][A-Za-z ,'-]+");
            assertThat(label).doesNotEndWith(".");
        });
        assertThat(JobLabels.labelOf("deactivateExpiredUrls")).isEqualTo("Turn off expired links");
    }

    @Test
    void aJobWithoutALabelShowsItsMethodName() {
        assertThat(JobLabels.labelOf("somethingNew")).isEqualTo("somethingNew");
    }

    private static List<String> scheduledMethodNames() {
        final var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) -> reader.getAnnotationMetadata().hasAnnotatedMethods(Scheduled.class.getName()));
        return scanner.findCandidateComponents(UrlShortenerApplication.class.getPackageName()).stream()
                .filter(candidate -> !Objects.requireNonNull(candidate.getResourceDescription()).contains("test-classes"))
                .map(candidate -> ClassUtils.resolveClassName(Objects.requireNonNull(candidate.getBeanClassName()), null))
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()).filter(method -> method.isAnnotationPresent(Scheduled.class)))
                .map(method -> method.getName())
                .toList();
    }

}
