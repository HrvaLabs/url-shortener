package cc.hrva.urlshortener.service.notification.channel;

import java.util.Map;

public record OutgoingRequest(String url, String contentType, Map<String, String> headers, byte[] body) {

    public static final String JSON = "application/json";

    @Override
    public String toString() {
        return "OutgoingRequest[contentType=" + contentType + ", headers=" + headers.keySet() + "]";
    }

}
