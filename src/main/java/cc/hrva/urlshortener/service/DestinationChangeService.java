package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.DestinationChangeResponse;
import cc.hrva.urlshortener.dto.LinkDestinationChangeResponse;
import cc.hrva.urlshortener.model.Url;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface DestinationChangeService {

    DestinationChangeResponse permissionFor(Url url, LinkEditor editor);
    void requireAllowed(Url url, LinkEditor editor);
    void countChange(Url url, LinkEditor editor);
    void record(Url url, String oldLongUrl, LinkEditor editor);
    Page<LinkDestinationChangeResponse> history(long urlId, String search, Pageable pageable);

}
