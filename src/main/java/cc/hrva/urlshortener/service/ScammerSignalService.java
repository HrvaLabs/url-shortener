package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.model.User;
import java.util.Collection;
import java.util.Set;

public interface ScammerSignalService {

    Set<Long> possibleScammers(Collection<User> users);
    Set<Long> possibleScammerIds();
    void invalidate();

}
