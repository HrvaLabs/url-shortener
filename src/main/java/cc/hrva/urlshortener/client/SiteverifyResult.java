package cc.hrva.urlshortener.client;

import java.util.List;

public sealed interface SiteverifyResult {

    record Answered(boolean success, String hostname, List<String> errorCodes) implements SiteverifyResult {

        public Answered {
            errorCodes = List.copyOf(errorCodes);
        }

    }

    record Unreachable(String reason) implements SiteverifyResult {
    }

}
