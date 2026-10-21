package cc.hrva.urlshortener.util;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;

public final class PublicAddresses {

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
        if (address instanceof Inet6Address) {
            return (bytes[0] & 0xFE) != 0xFC;
        }
        final var carrierGradeNat = (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 64;
        return !carrierGradeNat;
    }

}
