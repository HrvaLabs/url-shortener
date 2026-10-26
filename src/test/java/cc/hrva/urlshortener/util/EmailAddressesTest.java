package cc.hrva.urlshortener.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class EmailAddressesTest {

    @Test
    void keepsTheFirstCharacterAndTheDomain() {
        assertThat(EmailAddresses.masked("alice@example.com")).isEqualTo("a***@example.com");
    }

    @Test
    void aOneCharacterLocalPartStillGetsTheMask() {
        assertThat(EmailAddresses.masked("a@example.com")).isEqualTo("a***@example.com");
    }

    @Test
    void theDomainStartsAtTheLastAtSign() {
        assertThat(EmailAddresses.masked("\"a@b\"@example.com")).isEqualTo("\"***@example.com");
    }

    @Test
    void aCharacterOutsideTheBasicPlaneIsKeptWhole() {
        assertThat(EmailAddresses.masked("😀bob@example.com")).isEqualTo("😀***@example.com");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"alice", "@example.com"})
    void anythingWithoutALocalPartAndADomainIsMaskedWhole(final String address) {
        assertThat(EmailAddresses.masked(address)).isEqualTo("***");
    }

    @Test
    void masksEveryAddressInAText() {
        assertThat(EmailAddresses.maskedIn("New user registered: new@example.com, also bob@hrva.cc"))
                .isEqualTo("New user registered: n***@example.com, also b***@hrva.cc");
    }

    @Test
    void anAddressInAngleBracketsKeepsItsBrackets() {
        assertThat(EmailAddresses.maskedIn("Bob <bob@x.com>")).isEqualTo("Bob <b***@x.com>");
    }

    @Test
    void punctuationAfterAnAddressIsKept() {
        assertThat(EmailAddresses.maskedIn("Write to bob@x.com.")).isEqualTo("Write to b***@x.com.");
    }

    @Test
    void aTextWithoutAddressesIsUnchanged() {
        assertThat(EmailAddresses.maskedIn("A passkey was added to your account")).isEqualTo("A passkey was added to your account");
        assertThat(EmailAddresses.maskedIn(null)).isNull();
    }

}
