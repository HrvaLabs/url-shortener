package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.ReputationResult;

public interface DomainReputationService {

    ReputationResult lookup(String host);

}
