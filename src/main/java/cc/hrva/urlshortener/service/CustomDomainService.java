package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.domain.CustomDomainApplyDto;
import cc.hrva.urlshortener.dto.domain.CustomDomainResponse;
import cc.hrva.urlshortener.dto.domain.DomainRemovalResponse;
import cc.hrva.urlshortener.dto.domain.MyDomainsResponse;
import cc.hrva.urlshortener.model.enums.DomainLinkMode;

public interface CustomDomainService {

    MyDomainsResponse mine();
    CustomDomainResponse apply(CustomDomainApplyDto customDomainApplyDto);
    CustomDomainResponse get(long id);
    CustomDomainResponse retry(long id);
    DomainRemovalResponse remove(long id, DomainLinkMode mode);

}
