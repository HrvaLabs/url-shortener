package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.dto.SignInMethodResponse;
import cc.hrva.urlshortener.model.UserSignInMethod;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.repository.UserSignInMethodRepository;
import cc.hrva.urlshortener.service.SignInMethodService;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultSignInMethodService implements SignInMethodService {

    private static final Comparator<UserSignInMethod> PASSWORD_FIRST_THEN_FIRST_USED = Comparator
            .comparing((UserSignInMethod signInMethod) -> signInMethod.getMethod() != SignInMethod.PASSWORD)
            .thenComparing(UserSignInMethod::getFirstUsedAt)
            .thenComparing(UserSignInMethod::getMethod);

    private final UserRepository userRepository;
    private final UserSignInMethodRepository userSignInMethodRepository;

    @Override
    @Transactional
    public void record(final Long userId, final SignInMethod method, final LocalDateTime usedAt) {
        if (method == null) {
            log.info("Sign-in method not recorded, the pending sign-in carried none user id={}", userId);
            return;
        }
        if (userRepository.findByIdForUpdate(userId).isEmpty()) {
            return;
        }

        userSignInMethodRepository.recordUse(userId, method.name(), usedAt);
    }

    @Override
    public List<SignInMethodResponse> methodsOf(final Long userId) {
        return methodsOf(List.of(userId)).get(userId);
    }

    @Override
    public Map<Long, List<SignInMethodResponse>> methodsOf(final Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }

        final var byUser = userSignInMethodRepository.findByUserIdIn(userIds).stream()
                .collect(Collectors.groupingBy(UserSignInMethod::getUserId));

        return userIds.stream()
                .distinct()
                .collect(Collectors.toMap(Function.identity(), userId -> byUser.getOrDefault(userId, List.of()).stream()
                        .sorted(PASSWORD_FIRST_THEN_FIRST_USED)
                        .map(SignInMethodResponse::from)
                        .toList()));
    }

}
