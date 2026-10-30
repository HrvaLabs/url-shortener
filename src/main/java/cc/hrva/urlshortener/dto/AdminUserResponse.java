package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.codebook.Authorities;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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
        List<SignInMethodResponse> signInMethods,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean possibleScammer) {

    public static AdminUserResponse from(final User user, final List<SignInMethodResponse> signInMethods) {
        return from(user, signInMethods, null);
    }

    public static AdminUserResponse from(final User user, final Map<Long, List<SignInMethodResponse>> signInMethods, final Boolean possibleScammer) {
        return from(user, signInMethods.getOrDefault(user.getId(), List.of()), possibleScammer);
    }

    public static AdminUserResponse from(final User user, final List<SignInMethodResponse> signInMethods, final Boolean possibleScammer) {
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
                signInMethods == null ? List.of() : List.copyOf(signInMethods),
                possibleScammer);
    }

}
