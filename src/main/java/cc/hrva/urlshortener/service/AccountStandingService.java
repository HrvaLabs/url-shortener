package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.AccountStanding;
import cc.hrva.urlshortener.model.User;

public interface AccountStandingService {

    AccountStanding standingOf(User user);

}
