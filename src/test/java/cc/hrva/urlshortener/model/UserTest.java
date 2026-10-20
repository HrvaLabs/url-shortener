package cc.hrva.urlshortener.model;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class UserTest {

    @Test
    void neverSerializesThePasswordHashOrTwoFactorSecret() {
        final var user = User.builder().id(26L).email("someone@example.com").password("$2a$10$hash").twoFactorSecret("SECRET").build();

        final var json = JsonMapper.builder().build().writeValueAsString(user);

        assertThat(json).contains("someone@example.com").doesNotContain("password").doesNotContain("$2a$10$hash").doesNotContain("SECRET");
    }

    @Test
    void shouldSetLastLoginOnUserLoggedIn() {
        final var user = new User();
        user.userLoggedIn();

        assertThat(user.getLastLogin()).isNotNull();
        assertThat(user.getLastLogin()).isBeforeOrEqualTo(LocalDateTime.now());
    }

    @Test
    void shouldDefaultToActiveOnCreateWhenUnset() {
        final var user = new User();
        user.onCreate();

        assertThat(user.getCreateDate()).isNotNull();
        assertThat(user.getActive()).isTrue();
    }

    @Test
    void shouldPreserveExplicitActiveFlagOnCreate() {
        final var user = User.builder().active(false).build();
        user.onCreate();

        assertThat(user.getActive()).isFalse();
    }
}
