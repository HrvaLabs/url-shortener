package cc.hrva.urlshortener.mcp.bridge;

import cc.hrva.urlshortener.exception.ConnectRequestAlreadyApprovedException;
import cc.hrva.urlshortener.exception.ConnectRequestExpiredException;
import cc.hrva.urlshortener.security.SecureTokens;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class RedisConnectRequestStore implements ConnectRequestStore {

    private static final String REQUEST_KEY_PREFIX = "mcp:connect:request:";
    private static final String DECISION_KEY_PREFIX = "mcp:connect:decision:";
    private static final String CODE_KEY_PREFIX = "mcp:connect:code:";
    private static final String APPROVED = "approved";
    private static final String DENIED = "denied";

    private final StringRedisTemplate redisTemplate;
    private final JsonMapper jsonMapper;

    @Override
    public String save(final ConnectRequest connectRequest) {
        final var handle = SecureTokens.random();
        redisTemplate.opsForValue().set(REQUEST_KEY_PREFIX + handle, jsonMapper.writeValueAsString(connectRequest), REQUEST_TIME_TO_LIVE);

        return handle;
    }

    @Override
    public Optional<ConnectRequest> findPending(final String handle) {
        if (redisTemplate.opsForValue().get(DECISION_KEY_PREFIX + handle) != null) {
            return Optional.empty();
        }

        return find(handle);
    }

    @Override
    public String approve(final String handle, final String email) {
        final var connectRequest = find(handle).orElseThrow(ConnectRequestExpiredException::new);
        claimDecision(handle, APPROVED);

        final var code = SecureTokens.random();
        final var approvedConnectRequest = new ApprovedConnectRequest(handle, email, connectRequest.authorizationQuery());
        redisTemplate.opsForValue().set(CODE_KEY_PREFIX + code, jsonMapper.writeValueAsString(approvedConnectRequest), CODE_TIME_TO_LIVE);

        return code;
    }

    @Override
    public ConnectRequest deny(final String handle) {
        final var connectRequest = find(handle).orElseThrow(ConnectRequestExpiredException::new);
        claimDecision(handle, DENIED);
        redisTemplate.delete(REQUEST_KEY_PREFIX + handle);

        return connectRequest;
    }

    @Override
    public Optional<ApprovedConnectRequest> redeem(final String code) {
        final var storedApproval = redisTemplate.opsForValue().getAndDelete(CODE_KEY_PREFIX + code);
        if (storedApproval == null) {
            return Optional.empty();
        }

        final var approvedConnectRequest = jsonMapper.readValue(storedApproval, ApprovedConnectRequest.class);
        redisTemplate.delete(REQUEST_KEY_PREFIX + approvedConnectRequest.handle());

        return Optional.of(approvedConnectRequest);
    }

    private Optional<ConnectRequest> find(final String handle) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(REQUEST_KEY_PREFIX + handle))
                .map(storedRequest -> jsonMapper.readValue(storedRequest, ConnectRequest.class));
    }

    private void claimDecision(final String handle, final String decision) {
        final var decisionKey = DECISION_KEY_PREFIX + handle;
        if (Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(decisionKey, decision, REQUEST_TIME_TO_LIVE))) {
            return;
        }

        if (APPROVED.equals(redisTemplate.opsForValue().get(decisionKey))) {
            throw new ConnectRequestAlreadyApprovedException();
        }

        throw new ConnectRequestExpiredException();
    }

}
