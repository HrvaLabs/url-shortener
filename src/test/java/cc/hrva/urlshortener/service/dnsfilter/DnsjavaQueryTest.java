package cc.hrva.urlshortener.service.dnsfilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.DatagramPacket;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.Section;

class DnsjavaQueryTest {

    private DnsjavaQuery dnsjavaQuery;

    private DatagramSocket server;

    private final AtomicBoolean answering = new AtomicBoolean(true);

    private final AtomicBoolean truncating = new AtomicBoolean(false);

    private Thread serverThread;

    @BeforeEach
    void startStub() throws Exception {
        server = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        dnsjavaQuery = new DnsjavaQuery(server.getLocalPort());
        serverThread = Thread.startVirtualThread(() -> {
            final var buffer = new byte[512];
            while (!server.isClosed()) {
                try {
                    final var packet = new DatagramPacket(buffer, buffer.length);
                    server.receive(packet);
                    if (!answering.get()) {
                        continue;
                    }
                    final var query = new Message(Arrays.copyOf(packet.getData(), packet.getLength()));
                    final var response = new Message(query.getHeader().getID());
                    response.getHeader().setFlag(Flags.QR);
                    response.addRecord(query.getQuestion(), Section.QUESTION);
                    if (truncating.get()) {
                        response.getHeader().setFlag(Flags.TC);
                        final var truncated = response.toWire();
                        server.send(new DatagramPacket(truncated, truncated.length, packet.getSocketAddress()));
                        continue;
                    }
                    final var host = query.getQuestion().getName();
                    if (host.toString().startsWith("blocked.")) {
                        response.getHeader().setRcode(Rcode.NXDOMAIN);
                    } else if (host.toString().startsWith("listed.")) {
                        response.getHeader().setRcode(Rcode.NXDOMAIN);
                        response.addRecord(new SOARecord(host, DClass.IN, 3600, Name.fromString("cleanbrowsing.rpz.noc.org."),
                                Name.fromString("accesspolicy.rpz.noc.org."), 1, 7200, 900, 1209600, 86400), Section.AUTHORITY);
                    } else {
                        response.addRecord(new ARecord(host, DClass.IN, 60, InetAddress.getByName("203.0.113.7")), Section.ANSWER);
                    }
                    final var bytes = response.toWire();
                    server.send(new DatagramPacket(bytes, bytes.length, packet.getSocketAddress()));
                } catch (final Exception e) {
                    return;
                }
            }
        });
    }

    @AfterEach
    void stopStub() {
        server.close();
        serverThread.interrupt();
    }

    private String resolver() {
        return "127.0.0.1";
    }

    @Test
    void readsTheRcodeAndTheARecords() throws Exception {
        final var reply = dnsjavaQuery.ask(resolver(), "example.com", Duration.ofSeconds(2)).get(5, TimeUnit.SECONDS);

        assertThat(reply).isEqualTo(new DnsReply(Rcode.NOERROR, List.of("203.0.113.7"), 0, null));
    }

    @Test
    void readsAnNxdomainWithoutAddresses() throws Exception {
        final var reply = dnsjavaQuery.ask(resolver(), "blocked.example", Duration.ofSeconds(2)).get(5, TimeUnit.SECONDS);

        assertThat(reply).isEqualTo(new DnsReply(Rcode.NXDOMAIN, List.of(), 0, null));
    }

    @Test
    void readsTheAuthorityCountAndTheSoaPrimaryServer() throws Exception {
        final var reply = dnsjavaQuery.ask(resolver(), "listed.wicar.org", Duration.ofSeconds(2)).get(5, TimeUnit.SECONDS);

        assertThat(reply).isEqualTo(new DnsReply(Rcode.NXDOMAIN, List.of(), 1, "cleanbrowsing.rpz.noc.org"));
    }

    @Test
    void aTruncatedAnswerIsRetriedOverTcp() throws Exception {
        truncating.set(true);
        try (final var tcp = new ServerSocket(server.getLocalPort(), 1, InetAddress.getLoopbackAddress())) {
            final var tcpServer = Thread.startVirtualThread(() -> {
                try (final var socket = tcp.accept()) {
                    final var in = new DataInputStream(socket.getInputStream());
                    final var query = new byte[in.readUnsignedShort()];
                    in.readFully(query);
                    final var request = new Message(query);
                    final var response = new Message(request.getHeader().getID());
                    response.getHeader().setFlag(Flags.QR);
                    response.addRecord(request.getQuestion(), Section.QUESTION);
                    response.addRecord(new ARecord(request.getQuestion().getName(), DClass.IN, 60, InetAddress.getByName("198.51.100.9")), Section.ANSWER);
                    final var bytes = response.toWire();
                    final var out = new DataOutputStream(socket.getOutputStream());
                    out.writeShort(bytes.length);
                    out.write(bytes);
                    out.flush();
                } catch (final Exception e) {
                    return;
                }
            });

            final var reply = dnsjavaQuery.ask(resolver(), "example.com", Duration.ofSeconds(2)).get(5, TimeUnit.SECONDS);

            assertThat(reply).isEqualTo(new DnsReply(Rcode.NOERROR, List.of("198.51.100.9"), 0, null));
            tcpServer.join(1000);
        }
    }

    @Test
    void aSilentServerFailsTheFutureWithinTheTimeout() {
        answering.set(false);
        final var started = System.nanoTime();

        assertThatThrownBy(() -> dnsjavaQuery.ask(resolver(), "example.com", Duration.ofMillis(300)).get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }

}
