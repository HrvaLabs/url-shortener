package cc.hrva.urlshortener.service.dnsfilter;

import java.util.List;

public record DnsReply(int rcode, List<String> addresses, int authorityCount, String authorityMname) {

}
