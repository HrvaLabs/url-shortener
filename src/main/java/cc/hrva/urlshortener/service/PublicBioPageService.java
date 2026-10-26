package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.ClickRequest;
import cc.hrva.urlshortener.dto.bio.PublicBioPageResponse;

public interface PublicBioPageService {

    PublicBioPageResponse find(String handle);
    void recordView(String handle, ClickRequest click);

}
