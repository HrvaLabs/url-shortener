package cc.hrva.urlshortener.service.burst;

import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.service.ratelimit.RateLimiter;

public record CreatedLink(long urlId, String longUrl, String creator, LinkVerdict.Outcome outcome) {

    private static final String ACCOUNT_PREFIX = "user:";
    private static final String ANONYMOUS = "anonymous";

    public static String creatorOf(final Long ownerId, final String clientIp) {
        return ownerId != null ? ACCOUNT_PREFIX + ownerId : RateLimiter.clientKey(ANONYMOUS, clientIp);
    }

}
