package cc.hrva.urlshortener.client;

import java.net.InetAddress;
import java.util.function.Predicate;

public final class ScriptedFetchers {

    private ScriptedFetchers() {}

    public static PublicHttpFetcher resolvingWith(final Resolver resolver, final Predicate<InetAddress> addressPolicy) {
        return new PublicHttpFetcher(url -> true, resolver, addressPolicy);
    }

}
