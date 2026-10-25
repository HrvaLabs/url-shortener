package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.ReservedAliasDto;
import cc.hrva.urlshortener.dto.ReservedAliasResponse;
import java.util.List;

public interface ReservedAliasService {

    boolean isReserved(String alias);
    List<ReservedAliasResponse> list();
    ReservedAliasResponse reserve(ReservedAliasDto reservedAliasDto);
    void unreserve(long id);

}
