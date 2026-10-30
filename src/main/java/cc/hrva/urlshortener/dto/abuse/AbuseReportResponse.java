package cc.hrva.urlshortener.dto.abuse;

import cc.hrva.urlshortener.dto.campaign.ReportStatus;
import com.fasterxml.jackson.annotation.JsonValue;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;

public record AbuseReportResponse(List<Report> reports, List<NoContact> noContact) {

    public AbuseReportResponse {
        reports = List.copyOf(reports);
        noContact = List.copyOf(noContact);
    }

    public record Report(String registrar, String to, List<String> cc, String subject, String body, List<Domain> domains) {

        public Report {
            cc = List.copyOf(cc);
            domains = List.copyOf(domains);
        }

    }

    public record Domain(String domain, LocalDate registeredOn, ReportStatus report) {}

    public record NoContact(String domain, String registrar, boolean lookupFailed, Reason reason, ReportStatus report) {

        public static NoContact of(final String domain, final String registrar, final Reason reason, final ReportStatus report) {
            return new NoContact(domain, registrar, reason == Reason.LOOKUP_FAILED, reason, report);
        }

    }

    @RequiredArgsConstructor
    public enum Reason {

        NO_REGISTRY("noRegistry"),
        LOOKUP_FAILED("lookupFailed"),
        NO_ABUSE_EMAIL("noAbuseEmail"),
        NOT_REGISTERED("notRegistered"),
        SHARED_SUFFIX("sharedSuffix");

        private final String value;

        @JsonValue
        public String value() {
            return value;
        }

    }

}
