package cc.hrva.urlshortener.model;

import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.ThreatType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UrlTest {

    @Test
    void shouldClearForAnonymousUser() {
        final var owner = User.builder().id(1L).build();
        final var url = Url.builder().owner(owner).build();

        url.clearForAnonymousUser();

        assertThat(url.getOwner()).isNull();
    }

    @Test
    void shouldInitializeOnCreate() {
        final var url = new Url();
        url.onCreate();

        assertThat(url.getVisits()).isEqualTo(0L);
        assertThat(url.getCreateDate()).isNotNull();
        assertThat(url.isActive()).isTrue();
    }

    @Test
    void aNewLinkSavedBlockedStaysOff() {
        final var held = Url.builder().active(false).blockReason(BlockReason.CAMPAIGN).build();
        final var malware = Url.builder().active(false).threatType(ThreatType.MALWARE).build();
        final var plain = Url.builder().active(false).build();

        held.onCreate();
        malware.onCreate();
        plain.onCreate();

        assertThat(held.isActive()).isFalse();
        assertThat(malware.isActive()).isFalse();
        assertThat(plain.isActive()).isTrue();
    }

    @Test
    void shouldVerifyUrlValidityAndDeactivate() {
        final var url = Url.builder().visits(10L).visitLimit(10L).active(true).build();

        url.verifyUrlValidity(url);

        assertThat(url.isActive()).isFalse();
    }

    @Test
    void shouldVerifyUrlValidityAndKeepActive() {
        final var url = Url.builder().visits(5L).visitLimit(10L).active(true).build();

        url.verifyUrlValidity(url);

        assertThat(url.isActive()).isTrue();
    }
}
