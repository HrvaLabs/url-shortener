package cc.hrva.urlshortener.dto.abuse;

import cc.hrva.urlshortener.dto.campaign.ReportStatus;
import java.time.LocalDate;
import java.util.List;

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

    public record NoContact(String domain, String registrar, boolean lookupFailed, ReportStatus report) {}

}
