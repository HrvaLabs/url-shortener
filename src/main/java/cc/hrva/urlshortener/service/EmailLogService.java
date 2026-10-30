package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.model.EmailLog;
import cc.hrva.urlshortener.model.enums.EmailStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface EmailLogService {

    Page<EmailLog> emails(EmailStatus status, String search, Pageable pageable);

}
