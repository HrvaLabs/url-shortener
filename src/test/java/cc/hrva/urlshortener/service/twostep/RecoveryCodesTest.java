package cc.hrva.urlshortener.service.twostep;

import cc.hrva.urlshortener.model.TwoFactorRecoveryCode;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.TwoFactorRecoveryCodeRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecoveryCodesTest {

    private static final String CODE_FORMAT = "^[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$";

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);

    @Mock
    private TwoFactorRecoveryCodeRepository recoveryCodeRepository;

    private RecoveryCodes recoveryCodes;

    @BeforeEach
    void setUp() {
        recoveryCodes = new RecoveryCodes(recoveryCodeRepository, passwordEncoder);
    }

    @Test
    @SuppressWarnings("unchecked")
    void issuesTenCodesWhenNoneAreLeft() {
        final var user = User.builder().id(7L).build();
        when(recoveryCodeRepository.countByUserAndUsedFalse(user)).thenReturn(0L);
        final ArgumentCaptor<List<TwoFactorRecoveryCode>> stored = ArgumentCaptor.forClass(List.class);

        final var codes = recoveryCodes.issueIfNone(user);

        assertThat(RecoveryCodes.COUNT).isEqualTo(10);
        assertThat(codes).hasSize(RecoveryCodes.COUNT).doesNotHaveDuplicates().allMatch(code -> code.matches(CODE_FORMAT));
        final InOrder order = inOrder(recoveryCodeRepository);
        order.verify(recoveryCodeRepository).deleteByUser(user);
        order.verify(recoveryCodeRepository).saveAll(stored.capture());
        assertThat(stored.getValue()).hasSize(RecoveryCodes.COUNT).allSatisfy(code -> {
            assertThat(code.getUser()).isSameAs(user);
            assertThat(code.isUsed()).isFalse();
            assertThat(code.getCodeHash()).startsWith("$2a$").doesNotContain(codes.toArray(String[]::new));
        });
        for (int i = 0; i < RecoveryCodes.COUNT; i++) {
            assertThat(passwordEncoder.matches(codes.get(i), stored.getValue().get(i).getCodeHash())).isTrue();
        }
    }

    @Test
    void issuesNothingWhileUnusedCodesRemain() {
        final var user = User.builder().id(7L).build();
        when(recoveryCodeRepository.countByUserAndUsedFalse(user)).thenReturn(3L);

        assertThat(recoveryCodes.issueIfNone(user)).isEmpty();

        verify(recoveryCodeRepository, never()).deleteByUser(any());
        verify(recoveryCodeRepository, never()).saveAll(any());
    }

    @Test
    void countsOnlyUnusedCodes() {
        final var user = User.builder().id(7L).build();
        when(recoveryCodeRepository.countByUserAndUsedFalse(user)).thenReturn(4L);

        assertThat(recoveryCodes.remaining(user)).isEqualTo(4L);

        verify(recoveryCodeRepository, never()).count();
    }

    @Test
    void deletesEveryCodeOfTheUser() {
        final var user = User.builder().id(7L).build();

        recoveryCodes.deleteAll(user);

        verify(recoveryCodeRepository).deleteByUser(user);
    }

}
