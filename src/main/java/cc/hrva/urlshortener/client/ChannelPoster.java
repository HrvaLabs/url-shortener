package cc.hrva.urlshortener.client;

import java.time.Duration;
import java.util.Map;

@FunctionalInterface
public interface ChannelPoster {

    PostResult post(String url, String contentType, Map<String, String> headers, byte[] body, Duration timeout);

}
