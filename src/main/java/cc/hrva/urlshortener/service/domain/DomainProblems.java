package cc.hrva.urlshortener.service.domain;

import cc.hrva.urlshortener.model.enums.DomainProblem;

public final class DomainProblems {

    private DomainProblems() {
    }

    public static String text(final DomainProblem problem) {
        if (problem == null) {
            return null;
        }
        return switch (problem) {
            case DNS_NOT_FOUND -> "We can't see the CNAME record yet. DNS changes can take up to an hour.";
            case CAA_BLOCKED -> "Your domain's CAA records don't allow our certificate authority. Add a CAA record for pki.goog, or remove your CAA records.";
            case CERTIFICATE_TIMED_OUT -> "The certificate couldn't be issued in time. Check the CNAME record; we'll try again.";
            case CNAME_REMOVED -> "The CNAME record for this domain is gone, so its links stopped working. Add the record again.";
            case CLOUDFLARE_BLOCKED -> "This domain can't be set up. We've been told and will look into it.";
            case PROVISIONING_FAILED -> "Something went wrong setting up your domain. We've been told and will look into it.";
            case DNS_TIMED_OUT -> "We waited 14 days and never saw the CNAME record, so we stopped checking. Add the record first.";
        };
    }

}
