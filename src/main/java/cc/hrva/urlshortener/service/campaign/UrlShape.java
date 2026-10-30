package cc.hrva.urlshortener.service.campaign;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

public record UrlShape(String path, List<String> queryNames) {

    private static final String SHA_256 = "SHA-256";

    public UrlShape {
        queryNames = List.copyOf(queryNames);
    }

    public String key() {
        try {
            final var digest = MessageDigest.getInstance(SHA_256).digest((path + "\n" + String.join(",", queryNames)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (final NoSuchAlgorithmException exception) {
            throw new IllegalStateException(SHA_256 + " is not available", exception);
        }
    }

    public String display() {
        return queryNames.isEmpty() ? path : path + "?" + String.join("&", queryNames);
    }

}
