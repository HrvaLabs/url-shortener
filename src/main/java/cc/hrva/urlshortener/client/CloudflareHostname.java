package cc.hrva.urlshortener.client;

import java.util.List;

public record CloudflareHostname(String id, String hostname, String status, String sslStatus, List<String> verificationErrors,
                                 List<String> validationErrors) {

    public CloudflareHostname {
        verificationErrors = verificationErrors == null ? List.of() : List.copyOf(verificationErrors);
        validationErrors = validationErrors == null ? List.of() : List.copyOf(validationErrors);
    }

}
