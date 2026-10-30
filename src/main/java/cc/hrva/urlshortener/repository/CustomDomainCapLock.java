package cc.hrva.urlshortener.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
@RequiredArgsConstructor
public class CustomDomainCapLock {

    private static final String LOCK = "select id from custom_domain_cap_lock where id = 1 for update";
    private static final String READ_ONLY_TRANSACTION = "Locking the custom domain cap needs a read-write transaction";

    private final JdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock() {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalTransactionStateException(READ_ONLY_TRANSACTION);
        }

        jdbcTemplate.queryForObject(LOCK, Integer.class);
    }

}
