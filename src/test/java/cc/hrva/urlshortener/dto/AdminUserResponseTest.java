package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.ApiKey;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.codebook.Authorities;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class AdminUserResponseTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SignInMethodResponse GITHUB = new SignInMethodResponse(SignInMethod.GITHUB,
            LocalDateTime.of(2026, 10, 20, 8, 0), LocalDateTime.of(2026, 10, 28, 19, 45), null);

    @Test
    void anAdminUserRowHasTheJsonShapeOfTheUserEntityPlusItsSignInMethodsAndThePossibleScammerFlag() {
        final var user = fullUser();
        final ObjectNode entityJson = JSON.valueToTree(user);
        entityJson.set("signInMethods", JSON.valueToTree(List.of(GITHUB)));

        final JsonNode responseJson = JSON.valueToTree(AdminUserResponse.from(user, Map.of(27L, List.of(GITHUB)), true));

        assertThat(responseJson).isEqualTo(entityJson.put("possibleScammer", true));
        assertThat(responseJson.get("authProvider").asString()).isEqualTo("local");
        assertThat(responseJson.get("signInMethods").toString())
                .isEqualTo("[{\"method\":\"GITHUB\",\"firstUsedAt\":\"2026-10-20T08:00:00\",\"lastUsedAt\":\"2026-10-28T19:45:00\",\"turnedOffAt\":null}]");
    }

    @Test
    void aTurnedOffMethodShowsWhenItWasTurnedOff() {
        final var turnedOff = new SignInMethodResponse(SignInMethod.GOOGLE,
                LocalDateTime.of(2026, 3, 1, 8, 0), LocalDateTime.of(2026, 10, 2, 8, 0), LocalDateTime.of(2026, 10, 30, 11, 15));

        final JsonNode json = JSON.valueToTree(AdminUserResponse.from(fullUser(), List.of(turnedOff)));

        assertThat(json.get("signInMethods").get(0).get("turnedOffAt").asString()).isEqualTo("2026-10-30T11:15:00");
    }

    @Test
    void aRowWhoseUserHasNoRecordedMethodsHasAnEmptyList() {
        final JsonNode json = JSON.valueToTree(AdminUserResponse.from(fullUser(), Map.of(), false));

        assertThat(json.get("signInMethods").isArray()).isTrue();
        assertThat(json.get("signInMethods")).isEmpty();
    }

    @Test
    void anEditedUserHasTheJsonShapeOfTheUserEntityWithoutThePossibleScammerFlag() {
        final var user = fullUser();

        final JsonNode json = JSON.valueToTree(AdminUserResponse.from(user, List.of(GITHUB)));
        final ObjectNode entityJson = JSON.valueToTree(user);
        entityJson.set("signInMethods", JSON.valueToTree(List.of(GITHUB)));

        assertThat(json).isEqualTo(entityJson);
        assertThat(json.has("possibleScammer")).isFalse();
    }

    @Test
    void theRolesAreCopiedOutOfTheEntity() {
        final var user = fullUser();

        final var response = AdminUserResponse.from(user, List.of());
        user.getAuthorities().clear();

        assertThat(response.authorities()).extracting(Authorities::getName).containsExactly("ROLE_USER", "ROLE_ADMIN");
    }

    private static User fullUser() {
        return User.builder()
                .id(27L)
                .email("rhea@example.com")
                .password("secret-hash")
                .apiKeys(List.of(ApiKey.builder().id(3L).build()))
                .apiKeySlots(4L)
                .authorities(new ArrayList<>(List.of(
                        Authorities.builder().id(1L).name("ROLE_USER").active(true).build(),
                        Authorities.builder().id(2L).name("ROLE_ADMIN").active(true).build())))
                .createDate(LocalDateTime.of(2026, 10, 1, 9, 30, 15, 123_456_000))
                .lastLogin(LocalDateTime.of(2026, 10, 26, 22, 5))
                .active(true)
                .emailVerified(false)
                .twoFactorEnabled(true)
                .twoStepEnabled(false)
                .twoFactorSecret("JBSWY3DPEHPK3PXP")
                .webauthnUserHandle("handle")
                .authProvider("local")
                .build();
    }

}
