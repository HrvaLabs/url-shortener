package cc.hrva.urlshortener.dto.campaign;

import cc.hrva.urlshortener.model.ReportedDomain;
import java.time.LocalDateTime;

public record ReportStatus(LocalDateTime reportedAt, String reportedBy, String registrar, LocalDateTime suspendedAt) {

    public static ReportStatus from(final ReportedDomain reportedDomain) {
        return new ReportStatus(reportedDomain.getReportedAt(), reportedDomain.getReportedBy(), reportedDomain.getRegistrar(),
                reportedDomain.getSuspendedAt());
    }

}
