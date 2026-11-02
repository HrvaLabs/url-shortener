package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.domain.AdminDomainRequestResponse;
import cc.hrva.urlshortener.model.enums.DomainRequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CustomDomainRequestAdminService {

    Page<AdminDomainRequestResponse> list(DomainRequestStatus status, String search, Pageable pageable);
    AdminDomainRequestResponse approve(long id, String note, String performedBy);
    AdminDomainRequestResponse decline(long id, String note, String performedBy);

}
