package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.bio.BioHandleAvailabilityResponse;
import cc.hrva.urlshortener.dto.bio.BioHandleDto;
import cc.hrva.urlshortener.dto.bio.BioPageCreateDto;
import cc.hrva.urlshortener.dto.bio.BioPageResponse;
import cc.hrva.urlshortener.dto.bio.BioPageUpdateDto;

public interface BioPageService {

    BioPageResponse mine();
    BioPageResponse create(BioPageCreateDto bioPageCreateDto);
    BioPageResponse update(BioPageUpdateDto bioPageUpdateDto);
    BioPageResponse changeHandle(BioHandleDto bioHandleDto);
    void delete();
    BioHandleAvailabilityResponse availability(String handle);

}
