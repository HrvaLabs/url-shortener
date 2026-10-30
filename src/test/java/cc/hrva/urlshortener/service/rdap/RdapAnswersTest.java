package cc.hrva.urlshortener.service.rdap;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class RdapAnswersTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void readsRegistrarAbuseAndStatus() {
        final var record = RdapAnswers.found("omaehhm.com", fixture("omaehhm.com.json"));

        assertThat(record).isEqualTo(new DomainRecord("omaehhm.com", DomainRecord.Status.FOUND, LocalDate.of(2026, 8, 14), "NameSilo, LLC",
                "abuse@namesilo.com", List.of("client transfer prohibited")));
        assertThat(record.suspended()).isFalse();
    }

    @Test
    void topLevelAbuseEntityIsAFallback() {
        final var record = RdapAnswers.found("buokpky.com", fixture("held.json"));

        assertThat(record.registrar()).isEqualTo("NameSilo, LLC");
        assertThat(record.abuseEmail()).isEqualTo("Abuse@NameSilo.com");
    }

    @Test
    void theRegistrarsOwnAbuseEntityWinsOverATopLevelOne() {
        final var record = RdapAnswers.found("x.com", JSON.readTree("""
                {"entities": [
                  {"roles": ["abuse"], "vcardArray": ["vcard", [["email", {}, "text", "top@registry.example"]]]},
                  {"roles": ["technical", "registrar"], "vcardArray": ["vcard", [["fn", {}, "text", "Registrar One"]]],
                   "entities": [{"roles": ["abuse"], "vcardArray": ["vcard", [["email", {}, "text", " abuse@one.example "]]]}]},
                  {"roles": ["registrar"], "vcardArray": ["vcard", [["fn", {}, "text", "Registrar Two"]]]}
                ]}
                """));

        assertThat(record.registrar()).isEqualTo("Registrar One");
        assertThat(record.abuseEmail()).isEqualTo("abuse@one.example");
    }

    @Test
    void noRegistrarMeansNullNames() {
        final var record = RdapAnswers.found("example-shop.hr", fixture("no-abuse.json"));

        assertThat(record.status()).isEqualTo(DomainRecord.Status.FOUND);
        assertThat(record.registrar()).isNull();
        assertThat(record.abuseEmail()).isNull();
        assertThat(record.registeredOn()).isNull();
        assertThat(record.statuses()).containsExactly("active");
    }

    @Test
    void blankNamesAndAnUnreadableAnswerAreNull() {
        final var blank = RdapAnswers.found("x.com", JSON.readTree("""
                {"status": "active", "entities": [{"roles": ["registrar"], "vcardArray": ["vcard", [["fn", {}, "text", "  "]]],
                  "entities": [{"roles": ["abuse"], "vcardArray": ["vcard", [["email", {}, "text", ""]]]}]}]}
                """));

        assertThat(blank.registrar()).isNull();
        assertThat(blank.abuseEmail()).isNull();
        assertThat(blank.statuses()).isEmpty();
    }

    @Test
    void holdStatusesInEitherSpellingSuspend() {
        assertThat(RdapAnswers.found("buokpky.com", fixture("held.json")).suspended()).isTrue();
        for (final var status : List.of("clientHold", "serverHold", "client hold", "Server Hold")) {
            final var record = RdapAnswers.found("x.com", JSON.readTree("{\"status\": [\"" + status + "\"]}"));
            assertThat(record.suspended()).as(status).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending delete", "redemption period", "client transfer prohibited", "inactive", "server hold pending"})
    void pendingDeleteAloneDoesNot(final String status) {
        assertThat(RdapAnswers.found("x.com", JSON.readTree("{\"status\": [\"" + status + "\"]}")).suspended()).isFalse();
    }

    @Test
    void notFoundSuspendsAndTheOtherOutcomesDoNot() {
        assertThat(DomainRecord.notFound("x.com").suspended()).isTrue();
        assertThat(DomainRecord.failed("x.com").suspended()).isFalse();
        assertThat(DomainRecord.noRdapServer("x.hr").suspended()).isFalse();
        assertThat(DomainRecord.failed("x.com")).isEqualTo(new DomainRecord("x.com", DomainRecord.Status.FAILED, null, null, null, List.of()));
    }

    @Test
    void aRecordRoundTripsAsJson() {
        final var record = RdapAnswers.found("omaehhm.com", fixture("omaehhm.com.json"));

        final var json = JSON.writeValueAsString(record);

        assertThat(json).isEqualTo("{\"domain\":\"omaehhm.com\",\"status\":\"FOUND\",\"registeredOn\":\"2026-08-14\",\"registrar\":\"NameSilo, LLC\","
                + "\"abuseEmail\":\"abuse@namesilo.com\",\"statuses\":[\"client transfer prohibited\"]}");
        assertThat(JSON.readValue(json, DomainRecord.class)).isEqualTo(record);
    }

    @Test
    void theRegistrationDateIsReadInUtc() {
        assertThat(RdapAnswers.registeredOn(JSON.readTree("{\"events\": [{\"eventAction\": \"registration\", \"eventDate\": \"2026-09-15T22:30:00-05:00\"}]}")))
                .contains(LocalDate.of(2026, 9, 16));
        assertThat(RdapAnswers.registeredOn(JSON.readTree("{\"events\": [{\"eventAction\": \"registration\", \"eventDate\": \"2026-09-15\"}]}")))
                .contains(LocalDate.of(2026, 9, 15));
        assertThat(RdapAnswers.registeredOn(JSON.readTree("{\"events\": [{\"eventAction\": \"registration\", \"eventDate\": \"15.09.2026.\"}]}"))).isEmpty();
    }

    private static JsonNode fixture(final String name) {
        try (InputStream input = RdapAnswersTest.class.getResourceAsStream("/rdap/" + name)) {
            return JSON.readTree(input);
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

}
