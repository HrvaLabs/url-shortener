package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.BlocklistSourceDto;
import cc.hrva.urlshortener.dto.BlocklistSourceResponse;
import java.util.List;

public interface BlocklistSourceService {

    List<BlocklistSourceResponse> list();
    BlocklistSourceResponse create(BlocklistSourceDto blocklistSourceDto);
    BlocklistSourceResponse update(long id, BlocklistSourceDto blocklistSourceDto);
    void delete(long id);
    BlocklistSourceResponse refresh(long id);
    void refreshAll();
    void refreshDue();
    void reloadMissing();

}
