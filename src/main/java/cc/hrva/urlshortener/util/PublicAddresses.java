package cc.hrva.urlshortener.util;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;

public final class PublicAddresses {

    private static final List<AddressBlock> NON_PUBLIC_BLOCKS = Stream.of(
                    "0.0.0.0/8", "100.64.0.0/10", "192.0.0.0/24", "192.0.2.0/24", "198.18.0.0/15",
                    "198.51.100.0/24", "203.0.113.0/24", "240.0.0.0/4",
                    "::/96", "64:ff9b::/96", "64:ff9b:1::/48", "2001::/32", "2001:db8::/32", "2002::/16", "fc00::/7")
            .map(AddressBlock::of)
            .toList();

    private PublicAddresses() {}

    public static boolean isPublicTarget(final String url) {
        try {
            final var uri = URI.create(url);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                return false;
            }
            final var host = uri.getHost().startsWith("[") ? uri.getHost().substring(1, uri.getHost().length() - 1) : uri.getHost();
            final var addresses = InetAddress.getAllByName(host);
            return addresses.length > 0 && Arrays.stream(addresses).allMatch(PublicAddresses::isPublicAddress);
        } catch (final IllegalArgumentException | UnknownHostException exception) {
            return false;
        }
    }

    private static boolean isPublicAddress(final InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        final var bytes = address.getAddress();
        return NON_PUBLIC_BLOCKS.stream().noneMatch(block -> block.contains(bytes));
    }

    private record AddressBlock(byte[] network, int prefixLength) {

        private static AddressBlock of(final String cidr) {
            final var network = InetAddress.ofLiteral(StringUtils.substringBefore(cidr, "/")).getAddress();
            return new AddressBlock(network, Integer.parseInt(StringUtils.substringAfter(cidr, "/")));
        }

        private boolean contains(final byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            final var wholeBytes = prefixLength / 8;
            final var remainingBits = prefixLength % 8;
            if (!Arrays.equals(address, 0, wholeBytes, network, 0, wholeBytes)) {
                return false;
            }
            final var mask = 0xFF00 >> remainingBits & 0xFF;
            return remainingBits == 0 || (address[wholeBytes] & mask) == (network[wholeBytes] & mask);
        }

    }

}
