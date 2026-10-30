package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.AuditLogFilter;
import cc.hrva.urlshortener.model.AuditLog;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.repository.AuditLogRepository;
import cc.hrva.urlshortener.repository.specification.AuditLogSpecification;
import cc.hrva.urlshortener.service.AuditLogService;
import cc.hrva.urlshortener.util.SortWhitelist;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DefaultAuditLogService implements AuditLogService {

    private static final String PERFORMED_AT = "performedAt";
    private static final SortWhitelist AUDIT_SORTS = SortWhitelist.of(PERFORMED_AT)
            .byDefault(Sort.by(Sort.Order.desc(PERFORMED_AT)))
            .thenBy(Sort.Order.desc("id"));

    private final AuditLogRepository auditLogRepository;

    @Override
    @Transactional
    public void log(final String performedBy, final AuditAction action, final String targetType, final String targetIdentifier, final String details) {
        save(performedBy, action, false, targetType, targetIdentifier, details);
    }

    @Override
    @Transactional
    public void logFailure(final String performedBy, final AuditAction action, final String targetType, final String targetIdentifier,
                           final String details) {
        save(performedBy, action, true, targetType, targetIdentifier, details);
    }

    @Override
    public Page<AuditLog> getAuditLogs(final Pageable pageable, final AuditLogFilter filter) {
        final var sorted = AUDIT_SORTS.apply(pageable);
        var spec = Specification.<AuditLog>unrestricted();
        if (filter.action() != null) {
            spec = spec.and(AuditLogSpecification.hasAction(filter.action()));
        }
        if (StringUtils.isNotBlank(filter.search())) {
            spec = spec.and(AuditLogSpecification.searchMatches(filter.search().strip()));
        }
        if (filter.from() != null) {
            spec = spec.and(AuditLogSpecification.performedOnOrAfter(filter.from()));
        }
        if (filter.to() != null) {
            spec = spec.and(AuditLogSpecification.performedOnOrBefore(filter.to()));
        }
        return auditLogRepository.findAll(spec, sorted);
    }

    private void save(final String performedBy, final AuditAction action, final boolean failed, final String targetType,
                      final String targetIdentifier, final String details) {
        final var entry = AuditLog.builder()
                .performedBy(performedBy)
                .action(action)
                .failed(failed)
                .targetType(targetType)
                .targetIdentifier(targetIdentifier)
                .details(details)
                .build();
        auditLogRepository.save(entry);
        log.info("Audit: {} {}{} {} {} - {}", performedBy, action, failed ? " failed" : "", targetType, targetIdentifier, details);
    }

}
