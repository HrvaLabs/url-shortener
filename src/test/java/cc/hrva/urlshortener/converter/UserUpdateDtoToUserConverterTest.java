package cc.hrva.urlshortener.converter;

import java.util.Optional;
import cc.hrva.urlshortener.dto.UserUpdateDto;
import cc.hrva.urlshortener.exception.UserNotFoundException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserUpdateDtoToUserConverterTest {

    private UserUpdateDtoToUserConverter converter;

    @Mock
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        this.converter = new UserUpdateDtoToUserConverter(this.userRepository);
    }

    @Test
    void shouldConvert() {
        final UserUpdateDto userUpdateDto = UserUpdateDto.builder().id(1L).build();
        final User user = User.builder().id(1L).build();

        when(userRepository.findById(anyLong())).thenReturn(Optional.ofNullable(user));

        assertThat(converter.convert(userUpdateDto).getId()).isEqualTo(1L);
    }

    @Test
    void theLoadedUserIsLeftUntouched() {
        final var userUpdateDto = UserUpdateDto.builder().id(1L).email("new@example.com").apiKeySlots(9L).active(false).build();
        final var user = User.builder().id(1L).email("old@example.com").password("secret-hash").apiKeySlots(3L).active(true).build();

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        final var edited = converter.convert(userUpdateDto);

        assertThat(edited).isNotSameAs(user);
        assertThat(edited.getId()).isEqualTo(1L);
        assertThat(edited.getPassword()).isNull();
        assertThat(user.getEmail()).isEqualTo("old@example.com");
        assertThat(user.getApiKeySlots()).isEqualTo(3L);
        assertThat(user.getActive()).isTrue();
    }

    @Test
    void shouldApplyNonNullFields() {
        final UserUpdateDto userUpdateDto = UserUpdateDto.builder()
                .id(1L).email("new@example.com").apiKeySlots(9L).active(false).build();
        final User user = User.builder().id(1L).email("old@example.com").apiKeySlots(3L).active(true).build();

        when(userRepository.findById(anyLong())).thenReturn(Optional.of(user));

        final var result = converter.convert(userUpdateDto);

        assertThat(result.getEmail()).isEqualTo("new@example.com");
        assertThat(result.getApiKeySlots()).isEqualTo(9L);
        assertThat(result.getActive()).isFalse();
    }

    @Test
    void shouldNotOverwriteExistingFieldsWhenDtoValuesNull() {
        final UserUpdateDto userUpdateDto = UserUpdateDto.builder().id(1L).active(false).build();
        final User user = User.builder().id(1L).email("keep@example.com").apiKeySlots(3L).active(true).build();

        when(userRepository.findById(anyLong())).thenReturn(Optional.of(user));

        final var result = converter.convert(userUpdateDto);

        assertThat(result.getEmail()).isEqualTo("keep@example.com");
        assertThat(result.getApiKeySlots()).isEqualTo(3L);
        assertThat(result.getActive()).isFalse();
    }

    @Test
    void shouldFailConvert() {
        final UserUpdateDto userUpdateDto = UserUpdateDto.builder().id(1L).build();

        when(userRepository.findById(anyLong())).thenReturn(Optional.empty());

        assertThatCode(() -> converter.convert(userUpdateDto))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessage(String.format("User with ID %d has not been found", userUpdateDto.getId()));
    }
}