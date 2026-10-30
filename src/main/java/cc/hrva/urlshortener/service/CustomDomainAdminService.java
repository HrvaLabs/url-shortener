package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.domain.AdminDomainResponse;
import cc.hrva.urlshortener.dto.domain.CloudflareCheckResponse;
import cc.hrva.urlshortener.dto.domain.DomainAllowanceDto;
import cc.hrva.urlshortener.dto.domain.DomainAllowanceResponse;
import cc.hrva.urlshortener.dto.domain.DomainRemovalResponse;
import cc.hrva.urlshortener.dto.domain.DomainSummaryResponse;
import cc.hrva.urlshortener.dto.domain.ReconcileResponse;
import cc.hrva.urlshortener.model.enums.CustomDomainStatus;
import cc.hrva.urlshortener.model.enums.DomainLinkMode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CustomDomainAdminService {

    Page<AdminDomainResponse> list(CustomDomainStatus status, String search, Pageable pageable);
    DomainSummaryResponse summary();
    AdminDomainResponse approve(long id, String note, String performedBy);
    AdminDomainResponse reject(long id, String note, String performedBy);
    AdminDomainResponse retry(long id);
    AdminDomainResponse disable(long id, String note);
    AdminDomainResponse enable(long id);
    DomainRemovalResponse delete(long id, DomainLinkMode mode, String performedBy);
    ReconcileResponse reconcile();
    CloudflareCheckResponse checkCloudflare();
    DomainAllowanceResponse allowance(long userId);
    DomainAllowanceResponse setAllowance(long userId, DomainAllowanceDto domainAllowanceDto, String performedBy);
    void removeAllowance(long userId);

}
