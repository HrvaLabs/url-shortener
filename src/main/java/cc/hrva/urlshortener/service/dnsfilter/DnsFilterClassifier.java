package cc.hrva.urlshortener.service.dnsfilter;

import cc.hrva.urlshortener.configuration.properties.AppProperties;
import java.util.List;
import java.util.Optional;
import org.xbill.DNS.Rcode;

public final class DnsFilterClassifier {

    private static final int NOERROR = Rcode.NOERROR;
    private static final int NXDOMAIN = Rcode.NXDOMAIN;
    private static final String NO_ANSWER = "no answer";
    private static final String NO_ADDRESS = "no address";
    private static final String NXDOMAIN_TEXT = "NXDOMAIN";

    private DnsFilterClassifier() {
    }

    public static boolean exists(final List<Optional<DnsReply>> referenceReplies) {
        return referenceReplies.stream()
                .flatMap(Optional::stream)
                .anyMatch(reply -> reply.rcode() == NOERROR && !reply.addresses().isEmpty());
    }

    public static DnsFilterAnswer classify(final AppProperties.DnsFilters.Filter filter,
            final Optional<DnsReply> reply, final boolean exists) {
        final var provider = filter.getProvider();
        if (reply.isEmpty()) {
            return new DnsFilterAnswer(provider, DnsFilterStatus.NO_ANSWER, NO_ANSWER);
        }
        final var dnsReply = reply.get();
        if (dnsReply.rcode() != NOERROR && dnsReply.rcode() != NXDOMAIN) {
            return new DnsFilterAnswer(provider, DnsFilterStatus.NO_ANSWER, Rcode.string(dnsReply.rcode()));
        }
        final var sinkhole = dnsReply.addresses().stream().filter(filter.getBlockAnswers()::contains).findFirst();
        if (sinkhole.isPresent()) {
            return new DnsFilterAnswer(provider, DnsFilterStatus.BLOCKED, sinkhole.get());
        }
        if (dnsReply.addresses().isEmpty()) {
            final var text = dnsReply.rcode() == NXDOMAIN ? NXDOMAIN_TEXT : NO_ADDRESS;
            return new DnsFilterAnswer(provider, exists ? DnsFilterStatus.BLOCKED : DnsFilterStatus.NOT_BLOCKED, text);
        }
        return new DnsFilterAnswer(provider, DnsFilterStatus.NOT_BLOCKED, dnsReply.addresses().getFirst());
    }

}
