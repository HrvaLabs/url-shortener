package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.service.rdap.DomainRecord;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public interface DomainAgeService {

    Optional<LocalDate> registeredOn(String host);

    boolean isYoung(String host);

    Map<String, DomainRecord> records(Collection<String> domains, boolean fresh);

}
