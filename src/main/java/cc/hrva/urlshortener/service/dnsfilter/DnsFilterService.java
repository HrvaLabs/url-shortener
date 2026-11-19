package cc.hrva.urlshortener.service.dnsfilter;

public interface DnsFilterService {

    DnsFilterReport lookup(String host);

}
