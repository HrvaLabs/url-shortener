package cc.hrva.urlshortener.service;

public interface LinkPreviewService {

    LinkPreviewOutcome preview(long linkId, String url, String clientIp);

}
