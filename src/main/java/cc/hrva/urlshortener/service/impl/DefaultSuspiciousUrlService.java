package cc.hrva.urlshortener.service.impl;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import cc.hrva.urlshortener.service.SuspiciousUrlService;
import cc.hrva.urlshortener.util.DomainNames;
import java.lang.Character.UnicodeScript;
import java.net.URI;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
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

    private static final String PUNYCODE = "punycode domain";
    private static final String ACE_PREFIX = "xn--";
    private static final String MIXES_PREFIX = "mixes ";
    private static final String UNUSUAL_CHARACTERS = " with unusual characters";
    private static final String LABEL_SEPARATOR = "\\.";
    private static final int LAST_ASCII = 0x7F;

    private static final Map<Character, Character> LATIN_LOOKALIKES = Map.ofEntries(
            Map.entry('\u0430', 'a'), Map.entry('\u0435', 'e'), Map.entry('\u043e', 'o'), Map.entry('\u0440', 'p'),
            Map.entry('\u0441', 'c'), Map.entry('\u0443', 'y'), Map.entry('\u0445', 'x'), Map.entry('\u0456', 'i'),
            Map.entry('\u0458', 'j'), Map.entry('\u0455', 's'), Map.entry('\u04cf', 'l'), Map.entry('\u0501', 'd'),
            Map.entry('\u04bb', 'h'), Map.entry('\u051b', 'q'), Map.entry('\u051d', 'w'), Map.entry('\u0475', 'v'),
            Map.entry('\u03bf', 'o'), Map.entry('\u03c1', 'p'), Map.entry('\u03bd', 'v'), Map.entry('\u03b1', 'a'),
            Map.entry('\u03b9', 'i'), Map.entry('\u03ba', 'k'), Map.entry('\u03c5', 'u'), Map.entry('\u03c7', 'x'),
            Map.entry('\u03b3', 'y'), Map.entry('\u03f2', 'c'), Map.entry('\u03f3', 'j'),
            Map.entry('\u0131', 'i'), Map.entry('\u0251', 'a'), Map.entry('\u0261', 'g'), Map.entry('\u01bf', 'p'), Map.entry('\u0269', 'i'),
            Map.entry('\u0142', 'l'), Map.entry('\u0111', 'd'), Map.entry('\u00f8', 'o'), Map.entry('\u00f0', 'd'),
            Map.entry('\u0127', 'h'), Map.entry('\u0167', 't'));

    private static final Set<UnicodeScript> SHARED_SCRIPTS = EnumSet.of(UnicodeScript.COMMON, UnicodeScript.INHERITED);
    private static final List<Set<UnicodeScript>> USUAL_SCRIPT_MIXES = List.of(
            EnumSet.of(UnicodeScript.LATIN, UnicodeScript.HAN, UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA),
            EnumSet.of(UnicodeScript.LATIN, UnicodeScript.HAN, UnicodeScript.BOPOMOFO),
            EnumSet.of(UnicodeScript.LATIN, UnicodeScript.HAN, UnicodeScript.HANGUL));

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern MIDDLE_DOT_BETWEEN_LS = Pattern.compile("(?<=l)\u00b7(?=l)");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");
    private static final Pattern WORD_SEPARATORS = Pattern.compile("[^\\p{L}\\p{M}\\p{N}\\p{Cf}]+");
    private static final Pattern FORMAT_CHARACTERS = Pattern.compile("\\p{Cf}+");

    private final String ownHost;

    public DefaultSuspiciousUrlService(final AppProperties appProperties) {
        this.ownHost = DomainNames.hostOf(appProperties.getServerUrl()).orElse(null);
    }

    @Override
    public List<String> findReasons(final String longUrl) {
        final var hostName = DomainNames.hostNameOf(longUrl).orElse(null);
        if (hostName == null) {
            return List.of();
        }

        final var host = hostName.ascii();
        final var reasons = new ArrayList<String>();
        if (DomainNames.isIpLiteral(host) || IPV4.matcher(host).matches()) {
            reasons.add("IP address instead of a domain");
            return reasons;
        }

        final var labels = host.split(LABEL_SEPARATOR);
        final var unicodeLabels = hostName.unicode().split(LABEL_SEPARATOR);
        final var lookalikeLabels = Arrays.stream(unicodeLabels).map(DefaultSuspiciousUrlService::latinLookalike).toArray(String[]::new);
        final var domain = DomainNames.registrableDomain(host);

        if (isShortenerHost(host) || matchesDomain(host, PLATFORM_SHARE_DOMAINS) && !matchesDomain(host, TRUSTED_SHARE_DOMAINS)) {
            reasons.add("hides its destination behind another shortener");
        }
        if (matchesDomain(host, DYNAMIC_DNS_DOMAINS)) {
            reasons.add("free dynamic DNS host");
        }
        reasons.addAll(alphabetReasons(unicodeLabels));
        imitatedBrand(lookalikeLabels, domain).ifPresent(brand -> reasons.add(IMITATES_PREFIX + brand));
        if (!isOfficialBrandDomain(domain) && hasLure(lookalikeLabels, longUrl)) {
            reasons.add(LOGIN_LURE);
        }
        final var ending = labels[labels.length - 1];
        if (HIGH_RISK_ENDINGS.contains(ending)) {
            reasons.add("high-risk domain ending ." + ending);
        }

        return reasons;
    }

    @Override
    public boolean isShortener(final String longUrl) {
        return DomainNames.hostOf(longUrl).map(this::isShortenerHost).orElse(false);
    }

    @Override
    public List<String> findTextReasons(final String text) {
        final var words = wordsOf(text);
        final var reasons = new LinkedHashSet<String>();
        words.stream()
                .map(DefaultSuspiciousUrlService::scriptsOf)
                .filter(DefaultSuspiciousUrlService::isUnusualMix)
                .map(DefaultSuspiciousUrlService::mixedAlphabets)
                .forEach(reasons::add);
        if (words.stream().map(DefaultSuspiciousUrlService::wordLookalike).anyMatch(PATH_LURE_WORDS::contains)) {
            reasons.add(LOGIN_LURE);
        }
        return List.copyOf(reasons);
    }

    @Override
    public Optional<String> findImitatedBrand(final String text) {
        return wordsOf(text).stream()
                .map(DefaultSuspiciousUrlService::wordLookalike)
                .map(DefaultSuspiciousUrlService::brandResembledBy)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static List<String> wordsOf(final String text) {
        if (StringUtils.isBlank(text)) {
            return List.of();
        }
        return Arrays.stream(WORD_SEPARATORS.split(text))
                .map(word -> FORMAT_CHARACTERS.matcher(word).replaceAll(""))
                .filter(word -> word.codePoints().anyMatch(Character::isLetterOrDigit))
                .toList();
    }

    private static String wordLookalike(final String word) {
        return latinLookalike(word.toLowerCase(Locale.ROOT)).toLowerCase(Locale.ROOT);
    }

    private static Optional<String> brandResembledBy(final String word) {
        if (BRAND_DOMAINS.containsKey(word)) {
            return Optional.of(word);
        }
        return BRAND_DOMAINS.keySet().stream().filter(brand -> resembles(word, brand)).sorted().findFirst();
    }

    private static Set<String> alphabetReasons(final String[] unicodeLabels) {
        final var reasons = new LinkedHashSet<String>();
        for (final var unicodeLabel : unicodeLabels) {
            if (unicodeLabel.startsWith(ACE_PREFIX)) {
                reasons.add(PUNYCODE);
                continue;
            }
            final var label = MIDDLE_DOT_BETWEEN_LS.matcher(unicodeLabel).replaceAll("");
            final var scripts = scriptsOf(label);
            if (isUnusualMix(scripts)) {
                reasons.add(mixedAlphabets(scripts));
            } else if (label.codePoints().anyMatch(codePoint -> codePoint > LAST_ASCII && UnicodeScript.of(codePoint) != UnicodeScript.LATIN)) {
                reasons.add(PUNYCODE);
            }
        }
        return reasons;
    }

    private static Set<UnicodeScript> scriptsOf(final String label) {
        final var scripts = EnumSet.noneOf(UnicodeScript.class);
        label.codePoints()
                .mapToObj(UnicodeScript::of)
                .filter(script -> !SHARED_SCRIPTS.contains(script))
                .forEach(scripts::add);
        return scripts;
    }

    private static boolean isUnusualMix(final Set<UnicodeScript> scripts) {
        return scripts.size() > 1 && USUAL_SCRIPT_MIXES.stream().noneMatch(usual -> usual.containsAll(scripts));
    }

    private static String mixedAlphabets(final Set<UnicodeScript> scripts) {
        final var names = scripts.stream()
                .filter(script -> script != UnicodeScript.UNKNOWN)
                .map(script -> StringUtils.capitalize(script.name().toLowerCase(Locale.ROOT).replace('_', ' ')))
                .toList();
        final var alphabets = names.size() == 1
                ? names.getFirst()
                : String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
        return MIXES_PREFIX + alphabets + " letters" + (scripts.contains(UnicodeScript.UNKNOWN) ? UNUSUAL_CHARACTERS : "");
    }

    private static String latinLookalike(final String label) {
        final var folded = MARKS.matcher(Normalizer.normalize(label, Normalizer.Form.NFD)).replaceAll("");
        final var lookalike = new StringBuilder(folded.length());
        folded.chars().forEach(character -> lookalike.append(LATIN_LOOKALIKES.getOrDefault((char) character, (char) character)));
        return lookalike.toString();
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
                || official.contains(ANY_ENDING) && domain.split("\\.")[0].equals(brand)
                && DomainNames.isUnderRegistrySuffix(domain) && DomainNames.registryDomain(domain).equals(domain);
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

}
