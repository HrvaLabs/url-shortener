package cc.hrva.urlshortener.service.dnsfilter;

import cc.hrva.urlshortener.service.settings.SettingKey;

public enum DnsFilterProvider {

    QUAD9("Quad9", SettingKey.DNS_FILTER_QUAD9),
    CLOUDFLARE("Cloudflare", SettingKey.DNS_FILTER_CLOUDFLARE),
    CLEANBROWSING("CleanBrowsing", SettingKey.DNS_FILTER_CLEANBROWSING),
    CONTROL_D("Control D", SettingKey.DNS_FILTER_CONTROL_D);

    private final String label;
    private final SettingKey settingKey;

    DnsFilterProvider(final String label, final SettingKey settingKey) {
        this.label = label;
        this.settingKey = settingKey;
    }

    public String label() {
        return label;
    }

    public SettingKey settingKey() {
        return settingKey;
    }

}
