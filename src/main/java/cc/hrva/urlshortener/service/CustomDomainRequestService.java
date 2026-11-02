package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.domain.DomainRequestDto;
import cc.hrva.urlshortener.dto.domain.DomainRequestResponse;

public interface CustomDomainRequestService {

    DomainRequestResponse request(DomainRequestDto domainRequestDto);
    void withdraw(long id);

}
