package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.RemovePasswordDto;
import cc.hrva.urlshortener.dto.SignInMethodTurnOnDto;
import cc.hrva.urlshortener.dto.SignInMethodsResponse;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.SignInMethod;

public interface SignInMethodChangeService {

    SignInMethodsResponse turnOff(SignInMethod method);
    SignInMethodsResponse turnOn(SignInMethod method, SignInMethodTurnOnDto dto);
    SignInMethodsResponse removePassword(RemovePasswordDto dto);
    void passwordWasReset(User lockedUser);

}
