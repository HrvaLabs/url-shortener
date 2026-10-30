package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.SignInMethodResponse;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface SignInMethodService {

    void record(Long userId, SignInMethod method, LocalDateTime usedAt);
    boolean turnOff(Long userId, SignInMethod method, LocalDateTime turnedOffAt);
    boolean turnOn(Long userId, SignInMethod method);
    boolean isTurnedOff(Long userId, SignInMethod method);
    boolean passwordSet(User user);
    List<SignInMethodResponse> methodsOf(Long userId);
    Map<Long, List<SignInMethodResponse>> methodsOf(Collection<Long> userIds);

}
