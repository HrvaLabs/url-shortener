package cc.hrva.urlshortener.converter;

import cc.hrva.urlshortener.exception.UserNotFoundException;
import cc.hrva.urlshortener.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import cc.hrva.urlshortener.dto.UserUpdateDto;
import cc.hrva.urlshortener.model.User;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserUpdateDtoToUserConverter implements Converter<UserUpdateDto, User> {

    private final UserRepository userRepository;

    @Override
    public User convert(final UserUpdateDto userUpdateDto) {
        final var existingUser = userRepository.findById(userUpdateDto.getId())
                .orElseThrow(() -> new UserNotFoundException(String.format("User with ID %d has not been found",
                        userUpdateDto.getId())));
        final var editedUser = User.builder()
                .id(existingUser.getId())
                .email(existingUser.getEmail())
                .apiKeySlots(existingUser.getApiKeySlots())
                .active(existingUser.getActive())
                .build();

        if (userUpdateDto.getEmail() != null) {
            editedUser.setEmail(userUpdateDto.getEmail());
        }
        if (userUpdateDto.getApiKeySlots() != null) {
            editedUser.setApiKeySlots(userUpdateDto.getApiKeySlots());
        }
        if (userUpdateDto.getActive() != null) {
            editedUser.setActive(userUpdateDto.getActive());
        }
        return editedUser;
    }

}
