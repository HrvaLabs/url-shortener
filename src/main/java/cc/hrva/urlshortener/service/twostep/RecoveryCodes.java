package cc.hrva.urlshortener.service.twostep;

import cc.hrva.urlshortener.model.TwoFactorRecoveryCode;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.TwoFactorRecoveryCodeRepository;
import java.security.SecureRandom;
import java.util.List;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RecoveryCodes {

    public static final int COUNT = 10;

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int GROUP_LENGTH = 4;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TwoFactorRecoveryCodeRepository recoveryCodeRepository;
    private final PasswordEncoder passwordEncoder;

    public List<String> issueIfNone(final User user) {
        if (remaining(user) > 0) {
            return List.of();
        }

        recoveryCodeRepository.deleteByUser(user);
        final var plainCodes = generate();
        recoveryCodeRepository.saveAll(plainCodes.stream()
                .map(plain -> TwoFactorRecoveryCode.builder()
                        .user(user)
                        .codeHash(passwordEncoder.encode(plain))
                        .used(false)
                        .build())
                .toList());
        log.info("Recovery codes issued user id={}", user.getId());

        return plainCodes;
    }

    public void deleteAll(final User user) {
        recoveryCodeRepository.deleteByUser(user);
    }

    public long remaining(final User user) {
        return recoveryCodeRepository.countByUserAndUsedFalse(user);
    }

    private static List<String> generate() {
        return IntStream.range(0, COUNT)
                .mapToObj(i -> randomGroup() + "-" + randomGroup())
                .toList();
    }

    private static String randomGroup() {
        final var group = new StringBuilder(GROUP_LENGTH);
        for (int i = 0; i < GROUP_LENGTH; i++) {
            group.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }

        return group.toString();
    }

}
