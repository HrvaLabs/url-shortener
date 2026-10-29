package cc.hrva.urlshortener.service.notification;

public record ChannelTarget(String target, String accessToken, String chatId, String signingSecret) {

    @Override
    public String toString() {
        return "ChannelTarget[redacted]";
    }

}
