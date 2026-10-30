package cc.hrva.urlshortener.dto.domain;

public record DnsRecord(String type, String name, String target) {

    public static final String CNAME = "CNAME";

    public static DnsRecord cname(final String name, final String target) {
        return new DnsRecord(CNAME, name, target);
    }

}
