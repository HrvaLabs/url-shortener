package cc.hrva.urlshortener.service.abuse;

import cc.hrva.urlshortener.dto.BreakdownEntry;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.settings.SettingKey;
import cc.hrva.urlshortener.util.DomainNames;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AbuseReportComposer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final String NEW_LINE = "\n";
    private static final String INDENT = "  ";
    private static final String A_PHISHING_PAGE = "a phishing page";
    private static final int MAX_EXAMPLES = 3;
    private static final Pattern COUNTRY_CODE = Pattern.compile("[A-Z]{2}");

    private final SettingsService settingsService;

    public Email compose(final String registrar, final Collection<Domain> domains, final Collection<Link> links, final List<BreakdownEntry> countries) {
        final var sortedDomains = domains.stream().sorted(Comparator.comparing(Domain::domain)).toList();
        final var target = targetOf(links, sortedDomains.stream().map(Domain::domain).collect(Collectors.toSet()));
        final var subject = "Phishing: %s used in links to %s".formatted(count(sortedDomains.size(), "domain"), target);
        final var body = new StringBuilder()
                .append("Hello,").append(NEW_LINE).append(NEW_LINE)
                .append("I run hrva.cc, a free URL shortener. ").append(when(links))
                .append(", short links were made on hrva.cc without an account that lead to ").append(target)
                .append(". They used these domains").append(StringUtils.isBlank(registrar) ? "" : ", registered with " + registrar.strip())
                .append(':').append(NEW_LINE);
        sortedDomains.forEach(domain -> body.append(INDENT).append(domain.domain()).append(registration(domain.registeredOn())).append(NEW_LINE));
        body.append(NEW_LINE).append("Example destinations:").append(NEW_LINE);
        examples(links).forEach(example -> body.append(INDENT).append(example).append(NEW_LINE));
        body.append(NEW_LINE)
                .append("These links had ").append(count(links.stream().mapToLong(Link::clicks).sum(), "click")).append(" on hrva.cc")
                .append(countriesText(countries)).append(". ").append(turnedOff(links)).append(NEW_LINE).append(NEW_LINE)
                .append("Please suspend these domains under your abuse policy.").append(NEW_LINE).append(NEW_LINE)
                .append(settingsService.text(SettingKey.ABUSE_REPORTER_NAME)).append(NEW_LINE)
                .append("hrva.cc, ").append(String.join(", ", settingsService.addresses(SettingKey.ABUSE_REPORTER_ADDRESS)));
        return new Email(subject, body.toString());
    }

    private static String targetOf(final Collection<Link> links, final Set<String> domains) {
        return links.stream()
                .map(Link::finalUrl)
                .flatMap(finalUrl -> DomainNames.hostOf(finalUrl).stream())
                .filter(host -> !DomainNames.isIpLiteral(host))
                .filter(host -> !domains.contains(DomainNames.registrableDomain(host)))
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream()
                .min(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey)
                .orElse(A_PHISHING_PAGE);
    }

    private static String when(final Collection<Link> links) {
        final var first = links.stream().map(Link::createdOn).filter(Objects::nonNull).min(Comparator.naturalOrder());
        final var last = links.stream().map(Link::createdOn).filter(Objects::nonNull).max(Comparator.naturalOrder());
        if (first.isEmpty()) {
            return "Recently";
        }
        return first.get().equals(last.get())
                ? "On " + DATE.format(first.get())
                : "Between %s and %s".formatted(DATE.format(first.get()), DATE.format(last.get()));
    }

    private static String registration(final LocalDate registeredOn) {
        return registeredOn == null ? " (registration date unknown)" : " (registered " + DATE.format(registeredOn) + ")";
    }

    private static List<String> examples(final Collection<Link> links) {
        return links.stream()
                .sorted(Comparator.comparingLong(Link::clicks).reversed().thenComparingLong(Link::id))
                .map(Link::longUrl)
                .distinct()
                .limit(MAX_EXAMPLES)
                .toList();
    }

    private static String countriesText(final List<BreakdownEntry> countries) {
        final var named = countries.stream()
                .flatMap(country -> countryName(country.value()).map(name -> String.format(Locale.ENGLISH, "%s (%,d)", name, country.clicks())).stream())
                .toList();
        if (named.isEmpty()) {
            return "";
        }
        final var listed = named.size() == 1
                ? named.getFirst()
                : String.join(", ", named.subList(0, named.size() - 1)) + " and " + named.getLast();
        return ", mostly from " + listed;
    }

    private static Optional<String> countryName(final String code) {
        if (code == null || !COUNTRY_CODE.matcher(code).matches()) {
            return Optional.empty();
        }
        final var name = Locale.of("", code).getDisplayCountry(Locale.ENGLISH);
        return StringUtils.isBlank(name) || name.equalsIgnoreCase(code) ? Optional.empty() : Optional.of(name);
    }

    private static String turnedOff(final Collection<Link> links) {
        final var stillOn = links.stream().filter(Link::active).count();
        return stillOn == 0
                ? "The links are turned off on hrva.cc."
                : String.format(Locale.ENGLISH, "%,d of the %,d links are still on; I'm turning them off.", stillOn, links.size());
    }

    private static String count(final long amount, final String unit) {
        return String.format(Locale.ENGLISH, "%,d %s%s", amount, unit, amount == 1 ? "" : "s");
    }

    public record Domain(String domain, LocalDate registeredOn) {}

    public record Link(long id, String longUrl, String finalUrl, LocalDate createdOn, boolean active, long clicks) {}

    public record Email(String subject, String body) {}

}
