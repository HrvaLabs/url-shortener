package cc.hrva.urlshortener.client;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

record SelfSignedHttpsServer(HttpsServer server, ExecutorService executor) {

    private static final char[] PASSWORD = "throwaway".toCharArray();

    static SelfSignedHttpsServer start(final Path directory, final HttpHandler handler)
            throws IOException, InterruptedException, GeneralSecurityException {
        final var keyStore = generateKeyStore(directory);
        final var keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, PASSWORD);
        final var context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);

        final var executor = Executors.newCachedThreadPool();
        final var server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.setExecutor(executor);
        server.createContext("/", handler);
        server.start();
        return new SelfSignedHttpsServer(server, executor);
    }

    String url() {
        return "https://127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static KeyStore generateKeyStore(final Path directory)
            throws IOException, InterruptedException, GeneralSecurityException {
        final var file = directory.resolve("throwaway-" + System.nanoTime() + ".p12");
        final var keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        final var process = new ProcessBuilder(List.of(keytool, "-genkeypair", "-alias", "test", "-keyalg", "EC",
                "-groupname", "secp256r1", "-dname", "CN=127.0.0.1", "-ext", "SAN=ip:127.0.0.1", "-validity", "1",
                "-storetype", "PKCS12", "-keystore", file.toString(), "-storepass", new String(PASSWORD)))
                .redirectErrorStream(true)
                .start();
        final var output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
        }
        final var keyStore = KeyStore.getInstance("PKCS12");
        try (final var input = Files.newInputStream(file)) {
            keyStore.load(input, PASSWORD);
        }
        return keyStore;
    }

}
