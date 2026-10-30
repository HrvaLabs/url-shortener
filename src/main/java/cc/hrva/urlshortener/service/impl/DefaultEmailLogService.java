package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.model.EmailLog;
import cc.hrva.urlshortener.model.enums.EmailStatus;
import cc.hrva.urlshortener.repository.EmailLogRepository;
import cc.hrva.urlshortener.repository.specification.EmailLogSpecification;
import cc.hrva.urlshortener.service.EmailLogService;
import cc.hrva.urlshortener.util.SortWhitelist;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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
    public Page<EmailLog> emails(final EmailStatus status, final String search, final Pageable pageable) {
        final var sorted = EMAIL_SORTS.apply(pageable);
        var spec = Specification.<EmailLog>unrestricted();
        if (status != null) {
            spec = spec.and(EmailLogSpecification.hasStatus(status.name()));
        }
        if (StringUtils.isNotBlank(search)) {
            spec = spec.and(EmailLogSpecification.searchMatches(search.strip()));
        }
        return emailLogRepository.findAll(spec, sorted);
    }

}
