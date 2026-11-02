package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.AccountStanding;
import cc.hrva.urlshortener.model.User;
import java.util.Collection;
import java.util.Map;

public interface AccountStandingService {

    AccountStanding standingOf(User user);
    Map<Long, AccountStanding> standingsOf(Collection<User> users);

}
