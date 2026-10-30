package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.ReservedAliasDto;
import cc.hrva.urlshortener.dto.ReservedAliasResponse;
import java.util.List;

public interface ReservedAliasService {

    List<String> BUILT_IN_ALIASES = List.of(
            "actuator", "anon", "api", "auth", "error", "login", "logout", "mcp", "oauth2", "swagger-ui", "v3", "webjars");

    boolean isReserved(String alias);
    List<ReservedAliasResponse> list();
    ReservedAliasResponse reserve(ReservedAliasDto reservedAliasDto, String performedBy);
    void unreserve(long id, String performedBy);

}
