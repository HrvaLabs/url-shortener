package cc.hrva.urlshortener.client;

import java.time.Duration;

@FunctionalInterface
public interface BrowserPageFetcher {

    FetchResult fetch(String url, long maxBytes, Duration timeout);

}
