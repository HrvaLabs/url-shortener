package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.LinkDigestEntry;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.service.LinkDigestService;
import cc.hrva.urlshortener.service.SendingEmailService;
import cc.hrva.urlshortener.service.SuspiciousUrlService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultLinkDigestService implements LinkDigestService {

    private static final String NEW_ACCOUNT_NOTE = "account created in the last 7 days";

    private final UrlRepository urlRepository;
    private final SuspiciousUrlService suspiciousUrlService;
    private final SendingEmailService sendingEmailService;

    @Override
    @Transactional(readOnly = true)
    public void sendDailyDigest() {
        final var now = LocalDateTime.now();
        final var newAccountsSince = now.minusDays(7);

        final var entries = urlRepository.findByCreateDateAfter(now.minusDays(1)).stream()
                .map(url -> toEntry(url, newAccountsSince))
                .filter(Objects::nonNull)
                .toList();

        if (entries.isEmpty()) {
            log.info("Link digest: nothing to review");
            return;
        }

        log.info("Link digest: {} links to review", entries.size());
        sendingEmailService.sendLinkDigestToAdmin(entries);
    }

    private LinkDigestEntry toEntry(final Url url, final LocalDateTime newAccountsSince) {
        final var owner = url.getOwner();
        final var notes = new ArrayList<String>();
        if (owner != null && owner.getCreateDate() != null && owner.getCreateDate().isAfter(newAccountsSince)) {
            notes.add(NEW_ACCOUNT_NOTE);
        }
        notes.addAll(suspiciousUrlService.findReasons(url.getLongUrl()));

        if (notes.isEmpty()) {
            return null;
        }
        return new LinkDigestEntry(url.getShortUrl(), url.getLongUrl(), owner != null ? owner.getEmail() : null, notes);
    }

}
