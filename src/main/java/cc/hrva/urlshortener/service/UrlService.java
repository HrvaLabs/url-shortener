package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.CreateUrlDto;
import cc.hrva.urlshortener.dto.UnlockResponse;
import cc.hrva.urlshortener.dto.UrlResponse;
import cc.hrva.urlshortener.dto.UrlSearchDto;
import cc.hrva.urlshortener.dto.UrlUpdateDto;
import cc.hrva.urlshortener.model.PeekUrl;
import cc.hrva.urlshortener.model.Url;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.servlet.view.RedirectView;

public interface UrlService {

    Page<UrlResponse> getAllUrls(Pageable pageable, UrlSearchDto search);
    UrlResponse revokeUrl(Long id);
    UrlResponse activateUrl(Long id);
    void deleteUrl(Long id);
    void deactivateExpiredUrls();
    UrlResponse updateUrl(UrlUpdateDto url);
    Url getUrlByLongUrl(String longUrl);
    String generateShortUrl(Long length);
    UrlResponse saveUrlRouting(CreateUrlDto url, String clientIP);
    Page<UrlResponse> getAllMyUrls(String apiKey, Pageable pageable, UrlSearchDto search);
    List<String> getMyTags(String apiKey);
    PeekUrl peekUrlByShortUrl(String shortUrl);
    UrlResponse saveUrlWithApiKey(CreateUrlDto createUrlDto, String apiKey);
    UnlockResponse unlockUrl(String shortUrl, String password, String clientIP);
    RedirectView redirectResultUrl(String shortUrl, String clientIP);
    UrlResponse checkIPUniquenessAndReturnUrl(String shortUrl, String clientIP);
    byte[] exportMyUrlsAsCsv(String apiKey);

}
