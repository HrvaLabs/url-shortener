package cc.hrva.urlshortener.service.dnsfilter;

public interface DnsFilterCheckService {

    DnsFilterCheckResult check(long urlId);
    void checkLater(long urlId);

}
