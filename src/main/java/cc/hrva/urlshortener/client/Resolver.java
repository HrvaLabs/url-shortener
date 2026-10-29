package cc.hrva.urlshortener.client;

import java.net.InetAddress;
import java.net.UnknownHostException;

@FunctionalInterface
public interface Resolver {

    Resolver SYSTEM = InetAddress::getAllByName;

    InetAddress[] resolve(String host) throws UnknownHostException;

}
