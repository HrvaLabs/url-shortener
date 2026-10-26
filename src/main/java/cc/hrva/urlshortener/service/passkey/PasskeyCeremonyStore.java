package cc.hrva.urlshortener.service.passkey;

import java.time.Duration;
import java.util.Optional;

public interface PasskeyCeremonyStore {

    Duration TIME_TO_LIVE = Duration.ofMinutes(5);
    String UNAVAILABLE = "Passkeys aren't available right now. Try again in a minute.";
    String EXPIRED = "This request expired. Try again.";

    String save(PasskeyCeremony ceremony);
    Optional<PasskeyCeremony> consume(String ceremonyId);

}
