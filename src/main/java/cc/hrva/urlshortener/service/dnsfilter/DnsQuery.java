package cc.hrva.urlshortener.service.dnsfilter;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public interface DnsQuery {

    CompletableFuture<DnsReply> ask(String resolverAddress, String asciiHost, Duration timeout);

}
