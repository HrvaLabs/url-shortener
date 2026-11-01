package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.SessionService;
import cc.hrva.urlshortener.service.twostep.AccountLock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultSessionService implements SessionService {

    private static final String NOT_AUTHENTICATED = "Not authenticated";

    private final UserRepository userRepository;
    private final AccountLock accountLock;

    @Override
    @Transactional
    public void endEverywhere() {
        final var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new NoAuthorizationException(NOT_AUTHENTICATED);
        }
        final var user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new NoAuthorizationException(NOT_AUTHENTICATED));

        accountLock.lock(user).endSessions(Instant.now());
        log.info("Signed out everywhere user id={}", user.getId());
    }

}
