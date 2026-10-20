package cc.hrva.urlshortener.mcp.bridge;

import java.time.Duration;
import java.util.Optional;

public interface ConnectRequestStore {

    Duration REQUEST_TIME_TO_LIVE = Duration.ofMinutes(10);
    Duration CODE_TIME_TO_LIVE = Duration.ofSeconds(60);

    String save(ConnectRequest connectRequest);
    Optional<ConnectRequest> findPending(String handle);
    String approve(String handle, String email);
    ConnectRequest deny(String handle);
    Optional<ApprovedConnectRequest> redeem(String code);

}
