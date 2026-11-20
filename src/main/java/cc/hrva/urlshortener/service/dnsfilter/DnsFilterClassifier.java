package cc.hrva.urlshortener.service.dnsfilter;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.xbill.DNS.Rcode;

public final class DnsFilterClassifier {

    private static final int NOERROR = Rcode.NOERROR;
    private static final int NXDOMAIN = Rcode.NXDOMAIN;
    private static final String NO_ANSWER = "no answer";
    private static final String NO_ADDRESS = "no address";
    private static final String NXDOMAIN_TEXT = "NXDOMAIN";
    private static final String LABEL_SEPARATOR = ".";

    private DnsFilterClassifier() {
    }

    public static boolean exists(final List<Optional<DnsReply>> referenceReplies, final Collection<String> blockAnswers) {
        return referenceReplies.stream()
                .flatMap(Optional::stream)
                .filter(reply -> reply.rcode() == NOERROR)
                .flatMap(reply -> reply.addresses().stream())
                .anyMatch(address -> !blockAnswers.contains(address));
    }

    public static DnsFilterAnswer classify(final AppProperties.DnsFilters.Filter filter,
            final Optional<DnsReply> reply, final List<Optional<DnsReply>> referenceReplies) {
        final var provider = filter.getProvider();
        if (reply.isEmpty()) {
            return new DnsFilterAnswer(provider, DnsFilterStatus.NO_ANSWER, NO_ANSWER);
        }
        final var dnsReply = reply.get();
        if (dnsReply.rcode() != NOERROR && dnsReply.rcode() != NXDOMAIN) {
            return new DnsFilterAnswer(provider, DnsFilterStatus.NO_ANSWER, Rcode.string(dnsReply.rcode()));
        }
        final var sinkhole = dnsReply.addresses().stream()
                .filter(filter.getBlockAnswers()::contains)
                .filter(address -> !answeredByAReference(address, referenceReplies))
                .findFirst();
        if (sinkhole.isPresent()) {
            return new DnsFilterAnswer(provider, DnsFilterStatus.BLOCKED, sinkhole.get());
        }
        if (dnsReply.rcode() == NXDOMAIN) {
            return new DnsFilterAnswer(provider, isBlockingNxdomain(filter, dnsReply) ? DnsFilterStatus.BLOCKED : DnsFilterStatus.NOT_BLOCKED, NXDOMAIN_TEXT);
        }
        final var answer = dnsReply.addresses().isEmpty() ? NO_ADDRESS : dnsReply.addresses().getFirst();
        return new DnsFilterAnswer(provider, DnsFilterStatus.NOT_BLOCKED, answer);
    }

    private static boolean answeredByAReference(final String address, final List<Optional<DnsReply>> referenceReplies) {
        return referenceReplies.stream().flatMap(Optional::stream).anyMatch(reference -> reference.addresses().contains(address));
    }

    private static boolean isBlockingNxdomain(final AppProperties.DnsFilters.Filter filter, final DnsReply reply) {
        if (filter.isNxdomainWithoutAuthority() && reply.authorityCount() == 0) {
            return true;
        }
        final var suffix = filter.getNxdomainAuthoritySuffix();
        return StringUtils.isNotBlank(suffix) && reply.authorityMname() != null && isWithin(reply.authorityMname(), suffix);
    }

    private static boolean isWithin(final String name, final String suffix) {
        final var host = StringUtils.removeEnd(name.toLowerCase(Locale.ROOT), LABEL_SEPARATOR);
        final var zone = StringUtils.removeEnd(suffix.toLowerCase(Locale.ROOT), LABEL_SEPARATOR);
        return host.equals(zone) || host.endsWith(LABEL_SEPARATOR + zone);
    }

}
