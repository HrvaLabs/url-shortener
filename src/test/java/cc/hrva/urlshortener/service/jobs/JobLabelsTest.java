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
    void theKeyIsTheClassAndMethodSoFlushRefreshAndReloadCanBeToldApart() {
        assertThat(JobLabels.all().keySet()).allSatisfy(name -> assertThat(name).matches("[A-Z][A-Za-z]+\\.[a-z][A-Za-z]+"));
        assertThat(JobLabels.labelOf("DefaultDiscordAlertService.flush")).isEqualTo("Post Discord alerts");
        assertThat(JobLabels.labelOf("DefaultSettingsService.refresh")).isEqualTo("Reload the settings");
        assertThat(JobLabels.labelOf("DefaultClickCheckService.reload")).isEqualTo("Reload the click-time blocklists");
    }

    @Test
    void onlyThePlumbingThatKeepsCopiesAndMetricsInStepIsInternal() {
        assertThat(JobLabels.all().keySet().stream().filter(JobLabels::isInternal)).containsExactlyInAnyOrder(
                "DefaultSettingsService.refresh",
                "DefaultClickCheckService.reload",
                "DefaultScheduledService.reloadMissingBlocklists",
                "DefaultScheduledService.reloadCustomDomainRoutes",
                "InspectionGauges.refreshInspectionGauges");
        assertThat(JobLabels.isInternal("Unknown.job")).isFalse();
    }

    @Test
    void labelsAreReadableSentencesWithoutAFullStop() {
        assertThat(JobLabels.all().values()).allSatisfy(label -> {
            assertThat(label).matches("[A-Z][A-Za-z ,'-]+");
            assertThat(label).doesNotEndWith(".");
        });
        assertThat(JobLabels.labelOf("DefaultScheduledService.deactivateExpiredUrls")).isEqualTo("Turn off expired links");
    }

    @Test
    void aJobWithoutALabelShowsItsMethodName() {
        assertThat(JobLabels.labelOf("NewJobs.somethingNew")).isEqualTo("NewJobs.somethingNew");
    }

    private static List<String> scheduledMethodNames() {
        final var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) -> reader.getAnnotationMetadata().hasAnnotatedMethods(Scheduled.class.getName()));
        return scanner.findCandidateComponents(UrlShortenerApplication.class.getPackageName()).stream()
                .filter(candidate -> !Objects.requireNonNull(candidate.getResourceDescription()).contains("test-classes"))
                .map(candidate -> ClassUtils.resolveClassName(Objects.requireNonNull(candidate.getBeanClassName()), null))
                .flatMap(type -> Arrays.stream(type.getDeclaredMethods()).filter(method -> method.isAnnotationPresent(Scheduled.class)))
                .map(method -> method.getDeclaringClass().getSimpleName() + "." + method.getName())
                .toList();
    }

}
