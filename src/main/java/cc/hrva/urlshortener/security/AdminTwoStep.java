package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.model.codebook.Authorities;
import cc.hrva.urlshortener.model.User;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

public final class AdminTwoStep {

    public static final String REQUIRED = "Turn on two-step sign-in to use admin pages.";
    public static final String PENDING = "ADMIN_TWO_STEP_REQUIRED";
    private static final String ROLE_ADMIN = "ROLE_ADMIN";
    private static final String ROLE_USER = "ROLE_USER";

    private AdminTwoStep() {
    }

    public static boolean needsTwoStep(final User user) {
        return isAdmin(user) && !Boolean.TRUE.equals(user.getTwoStepEnabled());
    }

    public static boolean hasAdminPowers(final User user) {
        return user != null && isAdmin(user) && Boolean.TRUE.equals(user.getTwoStepEnabled());
    }

    public static String refusalFor(final User user, final String usual) {
        return user != null && needsTwoStep(user) ? REQUIRED : usual;
    }

    public static boolean pendingInThisSession() {
        final var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(PENDING::equals);
    }

    static List<SimpleGrantedAuthority> sessionAuthoritiesOf(final User user, final List<SimpleGrantedAuthority> roles) {
        if (!needsTwoStep(user)) {
            return roles;
        }
        final var withoutAdmin = roles.stream().filter(role -> !ROLE_ADMIN.equals(role.getAuthority())).toList();
        final var userRole = withoutAdmin.stream().anyMatch(role -> ROLE_USER.equals(role.getAuthority()))
                ? Stream.<SimpleGrantedAuthority>empty()
                : Stream.of(new SimpleGrantedAuthority(ROLE_USER));
        return Stream.of(withoutAdmin.stream(), userRole, Stream.of(new SimpleGrantedAuthority(PENDING))).flatMap(stream -> stream).toList();
    }

    private static boolean isAdmin(final User user) {
        return Objects.requireNonNullElse(user.getAuthorities(), List.<Authorities>of()).stream()
                .anyMatch(authority -> ROLE_ADMIN.equals(authority.getName()));
    }

}
