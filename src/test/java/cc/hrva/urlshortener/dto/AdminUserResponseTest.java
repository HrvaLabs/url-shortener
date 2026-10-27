package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.ApiKey;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.codebook.Authorities;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

class AdminUserResponseTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void anAdminUserRowHasTheJsonShapeOfTheUserEntityPlusThePossibleScammerFlag() {
        final var user = fullUser();
        final ObjectNode entityJson = JSON.valueToTree(user);

        final JsonNode responseJson = JSON.valueToTree(AdminUserResponse.from(user, true));

        assertThat(responseJson).isEqualTo(entityJson.put("possibleScammer", true));
    }

    @Test
    void anEditedUserHasTheJsonShapeOfTheUserEntityWithoutThePossibleScammerFlag() {
        final var user = fullUser();

        final JsonNode json = JSON.valueToTree(AdminUserResponse.from(user));
        final JsonNode entityJson = JSON.valueToTree(user);

        assertThat(json).isEqualTo(entityJson);
        assertThat(json.has("possibleScammer")).isFalse();
    }

    @Test
    void theRolesAreCopiedOutOfTheEntity() {
        final var user = fullUser();

        final var response = AdminUserResponse.from(user);
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
