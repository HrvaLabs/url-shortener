package cc.hrva.urlshortener.dto.inspection;

import cc.hrva.urlshortener.model.enums.InspectionState;
import java.time.LocalDateTime;

public record VirusTotalView(
        InspectionState state,
        String checkedUrl,
        Integer malicious,
        Integer suspicious,
        Integer harmless,
        Integer undetected,
        LocalDateTime analysedAt,
        LocalDateTime checkedAt,
        LocalDateTime submittedAt,
        String reportUrl,
        String failure,
        boolean configured) {

    private static final String REPORT = "https://www.virustotal.com/gui/url/";

    public static VirusTotalView of(final InspectionState state, final String checkedUrl, final String reportId, final Integer malicious,
                                    final Integer suspicious, final Integer harmless, final Integer undetected, final LocalDateTime analysedAt,
                                    final LocalDateTime checkedAt, final LocalDateTime submittedAt, final String failure, final boolean configured) {
        return new VirusTotalView(state, checkedUrl, malicious, suspicious, harmless, undetected, analysedAt, checkedAt, submittedAt,
                reportId == null ? null : REPORT + reportId, failure, configured);
    }

    public static VirusTotalView none(final boolean configured) {
        return new VirusTotalView(null, null, null, null, null, null, null, null, null, null, null, configured);
    }

}
