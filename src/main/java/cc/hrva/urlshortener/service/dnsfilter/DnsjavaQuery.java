package cc.hrva.urlshortener.service.dnsfilter;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.Section;
import org.xbill.DNS.SimpleResolver;
import org.xbill.DNS.Type;

@Slf4j
@Component
public class DnsjavaQuery implements DnsQuery {

    private static final int DNS_PORT = 53;

    private final int port;

    public DnsjavaQuery() {
        this(DNS_PORT);
    }

    DnsjavaQuery(final int port) {
        this.port = port;
    }

    @Override
    public CompletableFuture<DnsReply> ask(final String resolverAddress, final String asciiHost, final Duration timeout) {
        try {
            final var resolver = new SimpleResolver(new InetSocketAddress(InetAddress.getByName(resolverAddress), port));
            resolver.setTimeout(timeout);
            final var query = Message.newQuery(Record.newRecord(Name.fromString(asciiHost + "."), Type.A, DClass.IN));
            return resolver.sendAsync(query).toCompletableFuture().thenApply(DnsjavaQuery::toReply);
        } catch (final Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static DnsReply toReply(final Message message) {
        final List<String> addresses = message.getSection(Section.ANSWER).stream()
                .filter(ARecord.class::isInstance)
                .map(record -> ((ARecord) record).getAddress().getHostAddress())
                .toList();
        final var authority = message.getSection(Section.AUTHORITY);
        final var primaryServer = authority.stream()
                .filter(SOARecord.class::isInstance)
                .map(record -> ((SOARecord) record).getHost().toString(true))
                .findFirst()
                .orElse(null);
        return new DnsReply(message.getRcode(), addresses, authority.size(), primaryServer);
    }

}
