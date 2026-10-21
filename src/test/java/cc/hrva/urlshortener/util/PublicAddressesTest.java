package cc.hrva.urlshortener.util;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PublicAddressesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://[64:ff9b::7f00:1]/",
            "https://[64:ff9b::808:808]/",
            "https://[64:ff9b:1::a00:1]/",
            "https://[2001::1]/",
            "https://[::7f00:1]/",
            "https://[2001:db8::1]/",
            "https://[::ffff:127.0.0.1]/",
            "https://[2002:7f00:1::1]/",
            "https://[2002:808:808::1]/",
            "https://0.1.2.3/",
            "https://100.127.255.254/",
            "https://192.0.0.192/",
            "https://192.0.2.10/",
            "https://198.18.0.1/",
            "https://198.19.255.254/",
            "https://198.51.100.7/",
            "https://203.0.113.9/",
            "https://240.0.0.1/",
            "https://255.255.255.255/"
    })
    void refusesReservedAndTunnelledAddresses(final String url) {
        assertThat(PublicAddresses.isPublicTarget(url)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://1.1.1.1/",
            "https://100.128.0.1/",
            "https://192.0.1.1/",
            "https://198.17.255.254/",
            "https://198.20.0.1/",
            "https://223.255.255.254/",
            "https://[2606:4700:4700::1111]/",
            "https://[2001:4860:4860::8888]/",
            "https://[2001:db9::1]/",
            "https://[::ffff:1.1.1.1]/"
    })
    void allowsPublicAddressesNextToTheReservedBlocks(final String url) {
        assertThat(PublicAddresses.isPublicTarget(url)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"127.0.0.1, false", "10.0.0.1, false", "169.254.169.254, false", "1.1.1.1, true"})
    void ipv4MappedDnsAnswerIsJudgedByItsIpv4Address(final String ipv4, final boolean expectedPublic) throws UnknownHostException {
        final var mapped = new byte[16];
        mapped[10] = (byte) 0xFF;
        mapped[11] = (byte) 0xFF;
        System.arraycopy(InetAddress.ofLiteral(ipv4).getAddress(), 0, mapped, 12, 4);
        final var address = Inet6Address.getByAddress("h", mapped, 0);

        assertThat(address.getAddress()).hasSize(16);
        assertThat(PublicAddresses.isPublicAddress(address)).isEqualTo(expectedPublic);
    }

}
