package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.enums.CreatedVia;

public interface CreatedViaCount {

    CreatedVia getVia();

    long getLinks();

}
