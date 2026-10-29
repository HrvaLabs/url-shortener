package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.service.notification.DestinationCheckOutcome;

public interface DestinationCheckService {

    DestinationCheckOutcome checkAll();

    void runNightly();

}
