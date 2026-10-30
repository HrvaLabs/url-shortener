package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.model.enums.DomainProblem;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DomainProblemsTest {

    private static final Map<DomainProblem, String> TEXTS = new EnumMap<>(Map.of(
            DomainProblem.DNS_NOT_FOUND, "We can't see the CNAME record yet. DNS changes can take up to an hour.",
            DomainProblem.CAA_BLOCKED,
            "Your domain's CAA records don't allow our certificate authority. Add a CAA record for pki.goog, or remove your CAA records.",
            DomainProblem.CERTIFICATE_TIMED_OUT, "The certificate couldn't be issued in time. Check the CNAME record; we'll try again.",
            DomainProblem.CNAME_REMOVED, "The CNAME record for this domain is gone, so its links stopped working. Add the record again.",
            DomainProblem.CLOUDFLARE_BLOCKED, "This domain can't be set up. We've been told and will look into it.",
            DomainProblem.PROVISIONING_FAILED, "Something went wrong setting up your domain. We've been told and will look into it.",
            DomainProblem.DNS_TIMED_OUT, "We waited 14 days and never saw the CNAME record, so we stopped checking. Add the record first."));

    @Test
    void everyProblemHasItsExactText() {
        assertThat(TEXTS).containsOnlyKeys(DomainProblem.values());
        Arrays.stream(DomainProblem.values()).forEach(problem -> assertThat(DomainProblems.text(problem)).isEqualTo(TEXTS.get(problem)));
    }

    @Test
    void noProblemHasNoText() {
        assertThat(DomainProblems.text(null)).isNull();
    }

}
