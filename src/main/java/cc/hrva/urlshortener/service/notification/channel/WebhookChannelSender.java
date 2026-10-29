package cc.hrva.urlshortener.service.notification.channel;

import cc.hrva.urlshortener.model.enums.ChannelType;
import cc.hrva.urlshortener.service.notification.ChannelTarget;
import cc.hrva.urlshortener.service.notification.NotificationMessage;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class WebhookChannelSender implements ChannelSender {

    private static final String HMAC = "HmacSHA256";

    @Override
    public ChannelType type() {
        return ChannelType.WEBHOOK;
    }

    @Override
    public OutgoingRequest render(final ChannelTarget target, final String eventName, final String deliveryUid, final String json,
            final NotificationMessage message) {
        final var body = json.getBytes(StandardCharsets.UTF_8);
        final var headers = new LinkedHashMap<String, String>();
        headers.put("X-Hrva-Event", eventName);
        headers.put("X-Hrva-Delivery", deliveryUid);
        headers.put("X-Hrva-Signature", "sha256=" + HexFormat.of().formatHex(signature(target.signingSecret(), body)));
        return new OutgoingRequest(target.target(), OutgoingRequest.JSON, headers, body);
    }

    private static byte[] signature(final String secret, final byte[] body) {
        try {
            final var mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC));
            return mac.doFinal(body);
        } catch (final GeneralSecurityException exception) {
            throw new IllegalStateException("webhook body can't be signed");
        }
    }

}
