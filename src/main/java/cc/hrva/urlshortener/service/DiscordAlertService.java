package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.service.alert.AdminAlertEvent;

public interface DiscordAlertService {

    void onAdminAlert(AdminAlertEvent alert);
    void flush();

}
