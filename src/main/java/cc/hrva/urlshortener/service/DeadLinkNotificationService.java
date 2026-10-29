package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.service.notification.DestinationCheckOutcome;

public interface DeadLinkNotificationService {

    void notify(DestinationCheckOutcome outcome);

}
