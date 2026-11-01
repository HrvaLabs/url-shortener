package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.exception.ResetLinkExpiredException;
import cc.hrva.urlshortener.repository.ResetTokenRepository;
import cc.hrva.urlshortener.security.ResetTokens;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import cc.hrva.urlshortener.model.ResetToken;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.service.ResetTokenService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class DefaultResetTokenService implements ResetTokenService {

    private final AppProperties appProperties;
    private final ResetTokenRepository resetTokenRepository;
    private final Clock clock;

    @Override
    public void deactivateExpiredPasswordResetTokens() {
        deactivateResetTokens(resetTokenRepository.findByExpirationDateIsLessThanEqualAndActiveTrue(now()));
    }

    @Override
    public void deactivateActiveResetTokenIfExists(final User user) {
        deactivateResetTokens(resetTokenRepository.findByUserAndActiveTrue(user));
    }

    @Override
    public void deleteExpiredPasswordResetTokens() {
        final var resetTokens = resetTokenRepository.findByExpirationDateIsLessThanEqualAndActiveFalse(
                now().minusHours(appProperties.getIpRetentionDurationInHours()));

        resetTokenRepository.deleteAll(resetTokens);
        if (!resetTokens.isEmpty()) log.info("Deleted {} reset tokens", resetTokens.size());
    }

    @Override
    @Transactional
    public ResetToken claim(final String token) {
        final var resetToken = resetTokenRepository.findByTokenHashAndActiveTrue(ResetTokens.hash(token))
                .orElseThrow(ResetLinkExpiredException::new);
        if (resetTokenRepository.claim(resetToken.getId(), now()) == 0) {
            throw new ResetLinkExpiredException();
        }
        return resetToken;
    }

    @Override
    public String createTokenForUser(final User user) {
        final var token = ResetTokens.generate();
        resetTokenRepository.save(ResetToken.builder()
                .user(user)
                .tokenHash(ResetTokens.hash(token))
                .expirationDate(now().plusHours(appProperties.getResetTokenExpirationInHours()))
                .build());
        return token;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private void deactivateResetTokens(List<ResetToken> resetTokens) {
        resetTokens = resetTokens.stream()
                .map(resetToken -> {
                    resetToken.setActive(false);
                    return resetToken;
                })
                .toList();

        resetTokenRepository.saveAll(resetTokens);
        if (!resetTokens.isEmpty()) log.info("Deactivated {} reset tokens", resetTokens.size());
    }

}
