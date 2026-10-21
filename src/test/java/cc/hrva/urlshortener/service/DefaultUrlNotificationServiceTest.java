package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.service.impl.DefaultUrlNotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultUrlNotificationServiceTest {

    @Test
    void expiryNotificationsRunOutsideAReadOnlyTransactionSoTheEmailLogCanBeWritten() throws NoSuchMethodException {
        final var transactional = DefaultUrlNotificationService.class.getMethod("notifyExpiringUrls").getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
    }

}
