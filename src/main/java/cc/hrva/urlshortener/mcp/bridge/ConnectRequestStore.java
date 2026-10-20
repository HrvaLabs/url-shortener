package cc.hrva.urlshortener.mcp.bridge;

import java.util.Optional;

public interface ConnectRequestStore {

    String save(ConnectRequest connectRequest);
    Optional<ConnectRequest> findPending(String handle);
    String approve(String handle, String email);
    ConnectRequest deny(String handle);
    Optional<ApprovedConnectRequest> redeem(String code);

}
