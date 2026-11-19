package cc.hrva.urlshortener.service.dnsfilter;

public enum DnsFilterProvider {

    QUAD9("Quad9"),
    CLOUDFLARE("Cloudflare"),
    CLEANBROWSING("CleanBrowsing"),
    CONTROL_D("Control D");

    private final String label;

    DnsFilterProvider(final String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

}
