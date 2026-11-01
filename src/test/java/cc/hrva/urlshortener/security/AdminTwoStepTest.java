package cc.hrva.urlshortener.security;

import cc.hrva.urlshortener.model.codebook.Authorities;
import cc.hrva.urlshortener.model.User;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class AdminTwoStepTest {

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anAdminWithoutTwoStepSignsInAsAUserMarkedToTurnItOn() {
        assertThat(sessionRoles(account(false, "ROLE_ADMIN"))).containsExactlyInAnyOrder("ROLE_USER", AdminTwoStep.PENDING);
        assertThat(sessionRoles(account(null, "ROLE_USER", "ROLE_ADMIN"))).containsExactlyInAnyOrder("ROLE_USER", AdminTwoStep.PENDING);
    }

    @Test
    void anAdminWithTwoStepAndAUserKeepTheirRoles() {
        assertThat(sessionRoles(account(true, "ROLE_ADMIN"))).containsExactly("ROLE_ADMIN");
        assertThat(sessionRoles(account(false, "ROLE_USER"))).containsExactly("ROLE_USER");
    }

    @Test
    void theSessionKnowsAnAdminStillHasToTurnTwoStepOn() {
        SecurityContextHolder.getContext().setAuthentication(TokenProvider.authenticationOf(account(false, "ROLE_ADMIN"), "token"));
        assertThat(AdminTwoStep.pendingInThisSession()).isTrue();

        SecurityContextHolder.getContext().setAuthentication(TokenProvider.authenticationOf(account(true, "ROLE_ADMIN"), "token"));
        assertThat(AdminTwoStep.pendingInThisSession()).isFalse();

        SecurityContextHolder.clearContext();
        assertThat(AdminTwoStep.pendingInThisSession()).isFalse();
    }

    @Test
    void adminPowersOverOtherPeoplesThingsNeedTwoStepToo() {
        assertThat(AdminTwoStep.hasAdminPowers(account(true, "ROLE_ADMIN"))).isTrue();
        assertThat(AdminTwoStep.hasAdminPowers(account(false, "ROLE_ADMIN"))).isFalse();
        assertThat(AdminTwoStep.hasAdminPowers(account(true, "ROLE_USER"))).isFalse();
        assertThat(AdminTwoStep.hasAdminPowers(null)).isFalse();
    }

    @Test
    void aRefusalTellsAnAdminWithoutTwoStepToTurnItOn() {
        assertThat(AdminTwoStep.refusalFor(account(false, "ROLE_ADMIN"), "Not yours")).isEqualTo("Turn on two-step sign-in to use admin pages.");
        assertThat(AdminTwoStep.refusalFor(account(false, "ROLE_USER"), "Not yours")).isEqualTo("Not yours");
        assertThat(AdminTwoStep.refusalFor(null, "Not yours")).isEqualTo("Not yours");
    }

    @Test
    void aTokenIsStillMintedWithTheAccountsRealRoles() {
        assertThat(TokenProvider.authenticationOf(account(false, "ROLE_ADMIN")).getAuthorities())
                .extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
    }

    private static List<String> sessionRoles(final User user) {
        return TokenProvider.authenticationOf(user, "token").getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    private static User account(final Boolean twoStep, final String... roles) {
        return User.builder().id(1L).email("admin@hrva.cc").twoStepEnabled(twoStep)
                .authorities(Arrays.stream(roles).map(name -> {
                    final var role = new Authorities();
                    role.setName(name);
                    return role;
                }).toList())
                .build();
    }

}
