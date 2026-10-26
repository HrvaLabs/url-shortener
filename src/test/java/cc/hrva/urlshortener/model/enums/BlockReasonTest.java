package cc.hrva.urlshortener.model.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockReasonTest {

    @Test
    void manualBlocksAreTheAdminsOwnAndBlockedAccounts() {
        assertThat(BlockReason.MANUAL).containsExactlyInAnyOrder(BlockReason.ADMIN, BlockReason.USER_BLOCKED);
    }

    @Test
    void theManualSetCannotBeChanged() {
        assertThatThrownBy(() -> BlockReason.MANUAL.add(BlockReason.BLOCKLIST)).isInstanceOf(UnsupportedOperationException.class);
    }

}
