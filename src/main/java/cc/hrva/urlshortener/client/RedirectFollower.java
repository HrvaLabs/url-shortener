package cc.hrva.urlshortener.client;

import java.time.Duration;
import java.util.List;

@FunctionalInterface
public interface RedirectFollower {

    List<RedirectHop> followRedirects(String url, int maxHops, Duration budget);

}
