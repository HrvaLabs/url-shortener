package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.service.burst.CreatedLink;

public interface BurstAlertService {

    void linkCreated(CreatedLink link);

}
