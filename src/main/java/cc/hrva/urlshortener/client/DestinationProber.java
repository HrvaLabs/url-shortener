package cc.hrva.urlshortener.client;

import java.time.Duration;
import java.util.function.Consumer;

@FunctionalInterface
public interface DestinationProber {

    ProbeResult probe(String url, Duration budget, Consumer<String> beforeEachRequest);

    default ProbeResult probe(final String url, final Duration budget) {
        return probe(url, budget, host -> {});
    }

}
