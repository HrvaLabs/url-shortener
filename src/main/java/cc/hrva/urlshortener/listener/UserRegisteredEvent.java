package cc.hrva.urlshortener.listener;

import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.VerificationToken;

public record UserRegisteredEvent(User user, VerificationToken verificationToken) {}
