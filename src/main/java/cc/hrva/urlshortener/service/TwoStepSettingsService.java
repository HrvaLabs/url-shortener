package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.PasskeyOptionsResponse;
import cc.hrva.urlshortener.dto.TwoFactorCodeDto;
import cc.hrva.urlshortener.dto.TwoFactorEnableResponse;
import cc.hrva.urlshortener.dto.TwoStepDisableDto;
import cc.hrva.urlshortener.dto.TwoStepStatusResponse;

public interface TwoStepSettingsService {

    TwoStepStatusResponse status();
    TwoFactorEnableResponse enableWithPasskeys();
    PasskeyOptionsResponse disableOptions();
    void disable(TwoStepDisableDto dto);
    void removeTotp(TwoFactorCodeDto dto);

}
