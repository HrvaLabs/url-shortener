package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.WeeklyDigest;
import java.time.LocalDate;

public interface WeeklyDigestService {

    WeeklyDigest collect(LocalDate today);
    void sendWeeklyDigest();

}
