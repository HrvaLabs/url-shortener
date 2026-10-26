package cc.hrva.urlshortener.converter;

import java.time.LocalDateTime;
import java.util.Collections;
import cc.hrva.urlshortener.dto.UserDto;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.codebook.Authorities;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserToUserDtoConverterTest {

    private final UserToUserDtoConverter converter = new UserToUserDtoConverter();

    @Test
    void shouldConvertUserToUserDto() {
        final var createDate = LocalDateTime.now();
        final var lastLogin = LocalDateTime.now();
        final var authorities = Collections.singletonList(Authorities.builder().id(1L).name("ROLE_USER").build());
        final var user = User.builder()
                .id(1L)
                .email("test@example.com")
                .apiKeySlots(3L)
                .authorities(authorities)
                .createDate(createDate)
                .lastLogin(lastLogin)
                .twoFactorEnabled(false)
                .twoStepEnabled(true)
                .build();

        final var userDto = converter.convert(user);

        assertThat(userDto.getId()).isEqualTo(1L);
        assertThat(userDto.getEmail()).isEqualTo("test@example.com");
        assertThat(userDto.getApiKeySlots()).isEqualTo(3L);
        assertThat(userDto.getAuthorities()).isEqualTo(authorities);
        assertThat(userDto.getCreateDate()).isEqualTo(createDate);
        assertThat(userDto.getLastLogin()).isEqualTo(lastLogin);
        assertThat(userDto.getTwoFactorEnabled()).isFalse();
        assertThat(userDto.getTwoStepEnabled()).isTrue();
    }

    @Test
    void shouldConvertWithNullValues() {
        final var user = User.builder().build();

        final var userDto = converter.convert(user);

        assertThat(userDto.getId()).isNull();
        assertThat(userDto.getEmail()).isNull();
    }
}
