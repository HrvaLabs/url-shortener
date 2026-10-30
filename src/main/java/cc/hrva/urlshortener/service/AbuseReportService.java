package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.abuse.AbuseReportRequest;
import cc.hrva.urlshortener.dto.abuse.AbuseReportResponse;
import cc.hrva.urlshortener.dto.abuse.ReportedDomainsDto;

public interface AbuseReportService {

    AbuseReportResponse prepare(AbuseReportRequest request);

    void markReported(ReportedDomainsDto request, String performedBy);

    void recheck();

}
