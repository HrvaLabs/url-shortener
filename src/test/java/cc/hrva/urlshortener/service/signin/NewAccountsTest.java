package cc.hrva.urlshortener.service.signin;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.service.ScammerSignalService;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.service.settings.SettingsServiceFixtures;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NewAccountsTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:15:30Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private final SettingsService settingsService = SettingsServiceFixtures.defaults(new AppProperties());

    @Mock
    private ScammerSignalService scammerSignalService;

    private NewAccounts newAccounts;

    @BeforeEach
    void setUp() {
        lenient().when(scammerSignalService.possibleScammers(anyCollection())).thenReturn(Set.of());
        newAccounts = new NewAccounts(settingsService, scammerSignalService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void youngerThanTheSettingIsNew() {
        final var user = createdAt(NOW_LOCAL.minusDays(30).plusSeconds(1));

        assertThat(newAccounts.isNew(user)).isTrue();
        assertThat(newAccounts.isNew(createdAt(NOW_LOCAL.minusDays(2)))).isTrue();

        verify(scammerSignalService, never()).possibleScammers(anyCollection());
    }

    @Test
    void exactlyTheSettingIsNotNew() {
        SettingsServiceFixtures.change(settingsService, SettingKey.SIGN_IN_FORM_NEW_ACCOUNT_DAYS, 7);

        assertThat(newAccounts.isNew(createdAt(NOW_LOCAL.minusDays(7)))).isFalse();
        assertThat(newAccounts.isNew(createdAt(NOW_LOCAL.minusDays(7).plusSeconds(1)))).isTrue();
        assertThat(newAccounts.isNew(createdAt(NOW_LOCAL.minusDays(30)))).isFalse();
    }

    @Test
    void zeroDaysOnlyCountsPossibleScammers() {
        SettingsServiceFixtures.change(settingsService, SettingKey.SIGN_IN_FORM_NEW_ACCOUNT_DAYS, 0);
        final var justSignedUp = createdAt(NOW_LOCAL);
        final var possibleScammer = User.builder().id(8L).createDate(NOW_LOCAL.minusYears(2)).build();
        when(scammerSignalService.possibleScammers(List.of(possibleScammer))).thenReturn(Set.of(8L));

        assertThat(newAccounts.isNew(justSignedUp)).isFalse();
        assertThat(newAccounts.isNew(possibleScammer)).isTrue();
    }

    @Test
    void aNullCreateDateIsOld() {
        final var user = User.builder().id(7L).build();

        assertThat(newAccounts.isNew(user)).isFalse();

        verify(scammerSignalService).possibleScammers(List.of(user));
    }

    private static User createdAt(final LocalDateTime createDate) {
        return User.builder().id(7L).createDate(createDate).build();
    }

}
