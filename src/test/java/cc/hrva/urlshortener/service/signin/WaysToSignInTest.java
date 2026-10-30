package cc.hrva.urlshortener.service.signin;

import cc.hrva.urlshortener.model.enums.SignInMethod;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WaysToSignInTest {

    @Test
    void aPasswordAProviderOrAPasskeyIsAWayIn() {
        assertThat(new WaysToSignIn(true, Set.of(), 0).any()).isTrue();
        assertThat(new WaysToSignIn(false, Set.of(SignInMethod.GITHUB), 0).any()).isTrue();
        assertThat(new WaysToSignIn(false, Set.of(), 1).any()).isTrue();
        assertThat(new WaysToSignIn(false, Set.of(), 0).any()).isFalse();
    }

    @Test
    void onlyAPasswordOrAProviderIsAFirstStep() {
        assertThat(new WaysToSignIn(true, Set.of(), 0).anyFirstStep()).isTrue();
        assertThat(new WaysToSignIn(false, Set.of(SignInMethod.GOOGLE), 0).anyFirstStep()).isTrue();
        assertThat(new WaysToSignIn(false, Set.of(), 3).anyFirstStep()).isFalse();
    }

    @Test
    void removingAWayLeavesTheOthers() {
        final var ways = new WaysToSignIn(true, Set.of(SignInMethod.GOOGLE, SignInMethod.GITHUB), 1);

        assertThat(ways.withoutPassword()).isEqualTo(new WaysToSignIn(false, Set.of(SignInMethod.GOOGLE, SignInMethod.GITHUB), 1));
        assertThat(ways.withoutProvider(SignInMethod.GOOGLE)).isEqualTo(new WaysToSignIn(true, Set.of(SignInMethod.GITHUB), 1));
        assertThat(ways.withoutOnePasskey()).isEqualTo(new WaysToSignIn(true, Set.of(SignInMethod.GOOGLE, SignInMethod.GITHUB), 0));
        assertThat(ways.withoutOnePasskey().withoutOnePasskey().passkeys()).isZero();
        assertThat(new WaysToSignIn(false, Set.of(), 0).withoutProvider(SignInMethod.GITHUB).providers()).isEmpty();
    }

}
