package cc.hrva.urlshortener.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class OAuthChallengesTest {

    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    @Test
    void theRfc7636ExampleMatches() {
        assertThat(OAuthChallenges.wellFormed(CHALLENGE)).isTrue();
        assertThat(OAuthChallenges.matches(VERIFIER, CHALLENGE)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXkx", "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjX", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"})
    void anyOtherVerifierDoesnt(final String verifier) {
        assertThat(OAuthChallenges.matches(verifier, CHALLENGE)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-c", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cMx",
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw+cM", "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-c=", "plain"})
    void onlyAnS256ChallengeIsWellFormedAndNothingMatchesAnythingElse(final String challenge) {
        assertThat(OAuthChallenges.wellFormed(challenge)).isFalse();
        assertThat(OAuthChallenges.matches(VERIFIER, challenge)).isFalse();
    }

    @Test
    void theChallengeIsReadFromTheRequestOnlyWhenItWasKept() {
        final var request = new MockHttpServletRequest();
        assertThat(OAuthChallenges.of(request)).isEmpty();

        request.setAttribute(OAuthChallenges.ATTRIBUTE, CHALLENGE);
        assertThat(OAuthChallenges.of(request)).contains(CHALLENGE);
    }

}
