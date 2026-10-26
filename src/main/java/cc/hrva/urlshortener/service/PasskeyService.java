package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.PasskeyDeleteDto;
import cc.hrva.urlshortener.dto.PasskeyOptionsResponse;
import cc.hrva.urlshortener.dto.PasskeyRegistrationDto;
import cc.hrva.urlshortener.dto.PasskeyRegistrationOptionsDto;
import cc.hrva.urlshortener.dto.PasskeyRenameDto;
import cc.hrva.urlshortener.dto.PasskeyResponse;
import java.util.List;

public interface PasskeyService {

    PasskeyOptionsResponse registrationOptions(PasskeyRegistrationOptionsDto dto);
    PasskeyResponse register(PasskeyRegistrationDto dto);
    List<PasskeyResponse> list();
    PasskeyResponse rename(Long id, PasskeyRenameDto dto);
    void delete(Long id, PasskeyDeleteDto dto);

}
