package cc.hrva.urlshortener.service.twostep;

import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UserRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
public class AccountLock {

    private static final String READ_ONLY_TRANSACTION = "Locking an account needs a read-write transaction";
    private static final String NOT_AUTHENTICATED = "Not authenticated";

    private final UserRepository userRepository;
    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.MANDATORY)
    public User lock(final User user) {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalTransactionStateException(READ_ONLY_TRANSACTION);
        }

        final var locked = userRepository.findByIdForUpdate(user.getId())
                .orElseThrow(() -> new NoAuthorizationException(NOT_AUTHENTICATED));
        entityManager.refresh(locked);

        return locked;
    }

}
