package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.exception.NotSignedInException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.impl.DefaultSessionService;
import cc.hrva.urlshortener.service.twostep.AccountLock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultSessionServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AccountLock accountLock = mock(AccountLock.class);
    private final SessionService sessionService = new DefaultSessionService(userRepository, accountLock);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void signingOutEverywhereEndsEveryTokenIssuedBeforeThisSecond() {
        final var user = User.builder().id(7L).email("ana@hrva.cc").active(true).build();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("ana@hrva.cc", "jwt", List.of()));
        when(userRepository.findByEmail("ana@hrva.cc")).thenReturn(Optional.of(user));
        when(accountLock.lock(user)).thenReturn(user);
        final var before = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        sessionService.endEverywhere();

        assertThat(user.sessionEndedFor(before.minusSeconds(1))).isTrue();
        assertThat(user.sessionEndedFor(Instant.now())).isFalse();
    }

    @Test
    void signingOutEverywhereNeedsASignedInAccountAndAnswers401() {
        assertThatThrownBy(sessionService::endEverywhere).isInstanceOf(NotSignedInException.class);

        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("gone@hrva.cc", null, List.of()));
        when(userRepository.findByEmail("gone@hrva.cc")).thenReturn(Optional.empty());
        assertThatThrownBy(sessionService::endEverywhere).isInstanceOf(NotSignedInException.class);
    }

}
