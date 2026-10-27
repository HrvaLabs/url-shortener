package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.codebook.Authorities;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;

public record AdminUserResponse(
        Long id,
        String email,
        Long apiKeySlots,
        List<Authorities> authorities,
        LocalDateTime createDate,
        LocalDateTime lastLogin,
        Boolean active,
        Boolean emailVerified,
        Boolean twoFactorEnabled,
        Boolean twoStepEnabled,
        String authProvider,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean possibleScammer) {

    public static AdminUserResponse from(final User user) {
        return from(user, null);
    }

    public static AdminUserResponse from(final User user, final Boolean possibleScammer) {
        return new AdminUserResponse(
                user.getId(),
                user.getEmail(),
                user.getApiKeySlots(),
                user.getAuthorities() == null ? null : List.copyOf(user.getAuthorities()),
                user.getCreateDate(),
                user.getLastLogin(),
                user.getActive(),
                user.getEmailVerified(),
                user.getTwoFactorEnabled(),
                user.getTwoStepEnabled(),
                user.getAuthProvider(),
                possibleScammer);
    }

}
