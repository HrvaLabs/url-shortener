package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.service.SuspiciousUrlService;
import cc.hrva.urlshortener.util.DomainNames;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Service
public class DefaultSuspiciousUrlService implements SuspiciousUrlService {

    private static final String ANY_ENDING = "*";

    private static final Map<String, Set<String>> BRAND_DOMAINS = Map.ofEntries(
            Map.entry("njuskalo", Set.of("njuskalo.hr")),
            Map.entry("jofogas", Set.of("jofogas.hu")),
            Map.entry("paypal", Set.of("paypal.com", "paypal.me", "paypalobjects.com")),
            Map.entry("revolut", Set.of("revolut.com", "revolut.me")),
            Map.entry("apple", Set.of("apple.com")),
            Map.entry("icloud", Set.of("icloud.com")),
            Map.entry("microsoft", Set.of("microsoft.com")),
            Map.entry("outlook", Set.of("outlook.com")),
            Map.entry("google", Set.of(ANY_ENDING)),
            Map.entry("gmail", Set.of("gmail.com")),
            Map.entry("facebook", Set.of("facebook.com")),
            Map.entry("instagram", Set.of("instagram.com")),
            Map.entry("whatsapp", Set.of("whatsapp.com", "whatsapp.net")),
            Map.entry("netflix", Set.of("netflix.com")),
            Map.entry("amazon", Set.of(ANY_ENDING)),
            Map.entry("booking", Set.of("booking.com")),
            Map.entry("airbnb", Set.of(ANY_ENDING)),
            Map.entry("vinted", Set.of(ANY_ENDING)),
            Map.entry("ebay", Set.of(ANY_ENDING)),
            Map.entry("olx", Set.of(ANY_ENDING)),
            Map.entry("dhl", Set.of(ANY_ENDING)),
            Map.entry("coinbase", Set.of("coinbase.com")),
            Map.entry("binance", Set.of("binance.com")));

    private static final Set<String> SHORTENER_DOMAINS = Set.of(
            "bit.ly", "bitly.com", "bit.do", "t.ly", "tinyurl.com", "ow.ly", "is.gd", "v.gd",
            "buff.ly", "rebrand.ly", "cutt.ly", "shorturl.at", "rb.gy", "tiny.cc", "s.id", "bl.ink", "short.io",
            "shorte.st", "adf.ly", "qrco.de", "clck.ru", "u.to", "x.co", "soo.gd",
            "shrtco.de", "urlz.fr", "gg.gg", "cli.re", "han.gl", "t9y.me", "kaywa.me", "linklyhq.com", "iiil.io");

    private static final Set<String> PLATFORM_SHARE_DOMAINS = Set.of("goo.gl", "t.co", "lnkd.in", "ift.tt", "dlvr.it");

    private static final Set<String> TRUSTED_SHARE_DOMAINS = Set.of("app.goo.gl");

    private static final Set<String> DYNAMIC_DNS_DOMAINS = Set.of(
            "work.gd", "duckdns.org", "ddns.net", "hopto.org", "zapto.org", "sytes.net", "no-ip.org", "no-ip.biz",
            "serveftp.com", "myftp.org", "freeddns.org", "dynu.net", "ngrok.io", "ngrok.app", "ngrok-free.app",
            "trycloudflare.com", "loca.lt", "serveo.net", "000webhostapp.com");

    private static final Set<String> HIGH_RISK_ENDINGS = Set.of(
            "top", "xyz", "click", "link", "cam", "icu", "buzz", "monster", "rest", "bar", "cfd", "sbs", "lol",
            "quest", "zip", "mov", "life", "work", "cyou", "tk", "ml", "ga", "cf", "gq");

    private static final Set<String> HOST_LURES = Set.of("verif", "login", "signin", "secure", "banking", "wallet");
    private static final Set<String> HOST_LURE_WORDS = Set.of("auth", "account", "accounts");
    private static final Set<String> PATH_LURE_WORDS = Set.of(
            "verify", "verification", "login", "signin", "secure", "auth", "wallet", "unlock");

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");

    private final String ownHost;

    public DefaultSuspiciousUrlService(final AppProperties appProperties) {
        this.ownHost = hostOf(appProperties.getServerUrl()).orElse(null);
    }

    @Override
    public List<String> findReasons(final String longUrl) {
        final var host = hostOf(longUrl).orElse(null);
        if (host == null) {
            return List.of();
        }

        final var reasons = new ArrayList<String>();
        if (host.startsWith("[") || IPV4.matcher(host).matches()) {
            reasons.add("IP address instead of a domain");
            return reasons;
        }

        final var labels = host.split("\\.");
        final var domain = DomainNames.registrableDomain(host);

        if (isShortenerHost(host) || matchesDomain(host, PLATFORM_SHARE_DOMAINS) && !matchesDomain(host, TRUSTED_SHARE_DOMAINS)) {
            reasons.add("hides its destination behind another shortener");
        }
        if (matchesDomain(host, DYNAMIC_DNS_DOMAINS)) {
            reasons.add("free dynamic DNS host");
        }
        if (Arrays.stream(labels).anyMatch(label -> label.startsWith("xn--"))) {
            reasons.add("punycode domain");
        }
        imitatedBrand(labels, domain).ifPresent(brand -> reasons.add("imitates " + brand));
        if (!isOfficialBrandDomain(domain) && hasLure(labels, longUrl)) {
            reasons.add("login or verification lure");
        }
        final var ending = labels[labels.length - 1];
        if (HIGH_RISK_ENDINGS.contains(ending)) {
            reasons.add("high-risk domain ending ." + ending);
        }

        return reasons;
    }

    @Override
    public boolean isShortener(final String longUrl) {
        return hostOf(longUrl).map(this::isShortenerHost).orElse(false);
    }

    private boolean isShortenerHost(final String host) {
        return host.equals(ownHost) || matchesDomain(host, SHORTENER_DOMAINS);
    }

    private static boolean matchesDomain(final String host, final Set<String> domains) {
        return domains.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
    }

    private static Optional<String> imitatedBrand(final String[] labels, final String domain) {
        return Arrays.stream(labels)
                .flatMap(label -> Arrays.stream(label.split("-")))
                .flatMap(part -> BRAND_DOMAINS.keySet().stream().filter(brand -> resembles(part, brand)))
                .filter(brand -> !isOfficialDomainOf(brand, domain))
                .findFirst();
    }

    private static boolean resembles(final String part, final String brand) {
        return part.equals(brand) || brand.length() >= 6 && differsByOneLetter(part, brand);
    }

    private static boolean isOfficialBrandDomain(final String domain) {
        return BRAND_DOMAINS.keySet().stream().anyMatch(brand -> isOfficialDomainOf(brand, domain));
    }

    private static boolean isOfficialDomainOf(final String brand, final String domain) {
        final var official = BRAND_DOMAINS.get(brand);
        return official.contains(domain)
                || official.contains(ANY_ENDING) && domain.split("\\.")[0].equals(brand) && DomainNames.registryDomain(domain).equals(domain);
    }

    private static boolean hasLure(final String[] labels, final String longUrl) {
        final var hostParts = Arrays.stream(labels).flatMap(label -> Arrays.stream(label.split("-"))).toList();
        if (hostParts.stream().anyMatch(part -> HOST_LURE_WORDS.contains(part) || HOST_LURES.stream().anyMatch(part::contains))) {
            return true;
        }

        try {
            final var uri = URI.create(longUrl);
            final var pathAndQuery = (StringUtils.defaultString(uri.getRawPath()) + " " + StringUtils.defaultString(uri.getRawQuery()))
                    .toLowerCase(Locale.ROOT);
            return Arrays.stream(NON_ALPHANUMERIC.split(pathAndQuery)).anyMatch(PATH_LURE_WORDS::contains);
        } catch (final IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean differsByOneLetter(final String first, final String second) {
        if (first.length() != second.length()) {
            return false;
        }

        var differences = 0;
        for (int i = 0; i < first.length() && differences <= 1; i++) {
            if (first.charAt(i) != second.charAt(i)) {
                differences++;
            }
        }
        return differences == 1;
    }

    private static Optional<String> hostOf(final String value) {
        if (StringUtils.isBlank(value)) {
            return Optional.empty();
        }
        try {
            final var withScheme = value.contains("://") ? value : "https://" + value;
            return Optional.ofNullable(URI.create(withScheme).getHost())
                    .map(host -> StringUtils.removeEnd(host.toLowerCase(Locale.ROOT), "."));
        } catch (final IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

}
