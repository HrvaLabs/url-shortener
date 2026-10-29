package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.notification.ChannelTestResponse;
import cc.hrva.urlshortener.dto.notification.NotificationChannelRequest;
import cc.hrva.urlshortener.dto.notification.NotificationChannelResponse;
import cc.hrva.urlshortener.dto.notification.SigningSecretResponse;
import cc.hrva.urlshortener.model.NotificationChannel;
import cc.hrva.urlshortener.service.notification.ChannelTarget;
import java.util.List;

public interface NotificationChannelService {

    List<NotificationChannelResponse> mine();

    NotificationChannelResponse create(NotificationChannelRequest request);

    NotificationChannelResponse update(long id, NotificationChannelRequest request);

    void delete(long id);

    SigningSecretResponse newSigningSecret(long id);

    ChannelTestResponse test(long id);

    ChannelTarget openTarget(NotificationChannel channel);

}
