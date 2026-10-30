package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.model.EmailLog;
import cc.hrva.urlshortener.model.enums.EmailStatus;
import cc.hrva.urlshortener.repository.EmailLogRepository;
import cc.hrva.urlshortener.service.EmailLogService;
import cc.hrva.urlshortener.util.SortWhitelist;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DefaultEmailLogService implements EmailLogService {

    private static final String CREATED_AT = "createdAt";
    private static final SortWhitelist EMAIL_SORTS = SortWhitelist.of(CREATED_AT)
            .byDefault(Sort.by(Sort.Order.desc(CREATED_AT)))
            .thenBy(Sort.Order.desc("id"));

    private final EmailLogRepository emailLogRepository;

    @Override
    public Page<EmailLog> emails(final EmailStatus status, final Pageable pageable) {
        final var sorted = EMAIL_SORTS.apply(pageable);
        return status == null ? emailLogRepository.findAll(sorted) : emailLogRepository.findAllByStatus(status.name(), sorted);
    }

}
