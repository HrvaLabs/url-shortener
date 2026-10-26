package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.TwoFactorEnableResponse;
import cc.hrva.urlshortener.dto.TwoFactorSetupResponse;
import cc.hrva.urlshortener.model.User;

public interface TwoFactorService {

    TwoFactorSetupResponse setup();
    TwoFactorEnableResponse enable(String code);
    boolean verifyLoginCode(User user, String code);

}
