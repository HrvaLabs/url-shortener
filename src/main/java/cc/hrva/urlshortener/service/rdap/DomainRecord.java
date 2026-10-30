package cc.hrva.urlshortener.service.rdap;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record DomainRecord(String domain, Status status, LocalDate registeredOn, String registrar, String abuseEmail, List<String> statuses) {

    private static final Set<String> HOLD_STATUSES = Set.of("clienthold", "serverhold");

    public DomainRecord {
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
    }

    public static DomainRecord notFound(final String domain) {
        return new DomainRecord(domain, Status.NOT_FOUND, null, null, null, List.of());
    }

    public static DomainRecord noRdapServer(final String domain) {
        return new DomainRecord(domain, Status.NO_RDAP_SERVER, null, null, null, List.of());
    }

    public static DomainRecord failed(final String domain) {
        return new DomainRecord(domain, Status.FAILED, null, null, null, List.of());
    }

    public boolean suspended() {
        return status == Status.NOT_FOUND
                || status == Status.FOUND && statuses.stream().map(value -> value.replace(" ", "").toLowerCase(Locale.ROOT)).anyMatch(HOLD_STATUSES::contains);
    }

    public enum Status {

        FOUND,
        NOT_FOUND,
        NO_RDAP_SERVER,
        FAILED

    }

}
