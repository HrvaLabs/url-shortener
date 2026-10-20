package cc.hrva.urlshortener.mcp.apps;

import java.util.List;

public interface ConnectedAppsService {

    List<ConnectedAppDto> findConnectedApps(String principalName);
    void disconnect(String principalName, String clientId);
    int disconnectAll(String principalName);

}
