package cc.hrva.urlshortener.client;

import java.time.Duration;
import java.util.List;

@FunctionalInterface
public interface RedirectFollower {

    List<String> followRedirects(String url, int maxHops, Duration budget);

}
