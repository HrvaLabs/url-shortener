package cc.hrva.urlshortener.service.dnsfilter;

import cc.hrva.urlshortener.model.Url;
import java.util.List;

public interface DnsFilterCheckService {

    DnsFilterCheckResult check(long urlId);
    List<String> blockingFilters(Url url);
    void checkLater(long urlId);
    void recheckFresh();
    void sweep();

}
