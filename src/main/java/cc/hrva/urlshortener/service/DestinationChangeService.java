package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.DataExportDto.DestinationChangeExport;
import cc.hrva.urlshortener.dto.DestinationChangeResponse;
import cc.hrva.urlshortener.dto.DestinationChangeStanding;
import cc.hrva.urlshortener.dto.LinkDestinationChangeResponse;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.DestinationChangeOverride;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface DestinationChangeService {

    DestinationChangeResponse permissionFor(Url url, LinkEditor editor);
    void requireAllowed(Url url, LinkEditor editor);
    void countChange(Url url, LinkEditor editor);
    void record(Url url, String oldLongUrl, LinkEditor editor, boolean forced);
    Page<LinkDestinationChangeResponse> history(long urlId, String search, Pageable pageable);
    DestinationChangeStanding standingOf(User user);
    Map<Long, DestinationChangeStanding> standingsOf(Collection<User> users);
    DestinationChangeStanding standingOf(long userId);
    DestinationChangeStanding setOverride(long userId, DestinationChangeOverride override, String admin);
    List<DestinationChangeExport> exportFor(User user);

}
