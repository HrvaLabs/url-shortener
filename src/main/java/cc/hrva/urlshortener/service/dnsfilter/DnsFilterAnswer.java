package cc.hrva.urlshortener.service.dnsfilter;

public record DnsFilterAnswer(DnsFilterProvider provider, DnsFilterStatus status, String answer) {

}
