package cc.hrva.urlshortener.service.bio;

import cc.hrva.urlshortener.model.BioPage;
import cc.hrva.urlshortener.repository.BioPageRepository;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
public class BioPageLock {

    private static final String READ_ONLY_TRANSACTION = "Locking a bio page needs a read-write transaction";

    private final BioPageRepository bioPageRepository;
    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<BioPage> lock(final BioPage page) {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalTransactionStateException(READ_ONLY_TRANSACTION);
        }

        final var locked = bioPageRepository.findByIdForUpdate(page.getId());
        locked.ifPresent(entityManager::refresh);

        return locked;
    }

}
