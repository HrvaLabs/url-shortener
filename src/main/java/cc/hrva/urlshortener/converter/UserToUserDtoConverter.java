package cc.hrva.urlshortener.converter;

import cc.hrva.urlshortener.dto.UserDto;
import cc.hrva.urlshortener.model.User;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class UserToUserDtoConverter implements Converter<User, UserDto> {

    @Override
    public UserDto convert(User user) {
        return UserDto.builder()
                .id(user.getId())
                .email(user.getEmail())
                .apiKeySlots(user.getApiKeySlots())
                .authorities(user.getAuthorities())
                .createDate(user.getCreateDate())
                .lastLogin(user.getLastLogin())
                .authProvider(user.getAuthProvider())
                .twoFactorEnabled(user.getTwoFactorEnabled())
                .twoStepEnabled(user.getTwoStepEnabled())
                .build();
    }

}
