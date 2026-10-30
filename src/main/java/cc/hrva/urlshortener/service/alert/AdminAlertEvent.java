package cc.hrva.urlshortener.service.alert;

import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.DomainDisableReason;
import cc.hrva.urlshortener.model.enums.DomainProblem;
import cc.hrva.urlshortener.model.enums.ReportCategory;
import java.time.Duration;
import java.util.List;

public sealed interface AdminAlertEvent {

    record ReviewOpened(AlertedLink link, List<String> reasons) implements AdminAlertEvent {}

    record VisitorReported(AlertedLink link, ReportCategory category, String details, boolean reviewOpened) implements AdminAlertEvent {}

    record LinkBlocked(AlertedLink link, BlockReason reason, String detail) implements AdminAlertEvent {}

    record UserSignedUp(Long userId, String authProvider) implements AdminAlertEvent {}

    record BlocklistSourceFailed(String sourceName, String error, boolean fallbackLoaded) implements AdminAlertEvent {}

    record BlocklistSourceRecovered(String sourceName) implements AdminAlertEvent {}

    record BurstDetected(String domain, int links, int creators, Integer domainAgeDays, Duration window) implements AdminAlertEvent {}

    record SameShapeDetected(String shape, String path, int links, int domains, Duration window, List<String> exampleAliases)
            implements AdminAlertEvent {}

    record AnonymousLinkBusy(AlertedLink link, long clicks, String topCountry) implements AdminAlertEvent {}

    record AnonymousTrafficShare(long anonymousClicks, long totalClicks) implements AdminAlertEvent {}

    record BioPageReviewOpened(AlertedBioPage page, List<String> reasons) implements AdminAlertEvent {}

    record BioPageReported(AlertedBioPage page, ReportCategory category, String details, boolean reviewOpened) implements AdminAlertEvent {}

    record DomainApplied(long domainId, String hostname, long userId, List<String> flags) implements AdminAlertEvent {

        public DomainApplied {
            flags = List.copyOf(flags);
        }

    }

    record DomainDisabled(long domainId, String hostname, DomainDisableReason reason, String note) implements AdminAlertEvent {}

    record DomainNeedsAttention(long domainId, String hostname, DomainProblem problem) implements AdminAlertEvent {}

    record DomainDrift(List<String> unknownHostnames, List<String> missingHostnames, Integer cloudflareTotal, int freeAllowance)
            implements AdminAlertEvent {

        public DomainDrift {
            unknownHostnames = List.copyOf(unknownHostnames);
            missingHostnames = List.copyOf(missingHostnames);
        }

    }

}
