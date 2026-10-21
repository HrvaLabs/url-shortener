package cc.hrva.urlshortener.service;

import java.time.LocalDate;
import java.util.Optional;

public interface DomainAgeService {

    Optional<LocalDate> registeredOn(String host);

    boolean isYoung(String host);

}
