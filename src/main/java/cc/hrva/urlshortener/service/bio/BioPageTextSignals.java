package cc.hrva.urlshortener.service.bio;

import cc.hrva.urlshortener.service.SuspiciousUrlService;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BioPageTextSignals {

    private static final String HANDLE = "handle";
    private static final String NAME = "name";
    private static final String BIO = "bio";
    private static final String BUTTON_TITLE = "a button title";
    private static final String HAS_A = "has a ";

    private final SuspiciousUrlService suspiciousUrlService;

    public List<String> reasonsFor(final String handle, final String displayName, final String bio, final List<String> buttonTitles) {
        final var reasons = new LinkedHashSet<String>();
        suspiciousUrlService.findBrandInHandle(handle).map(brand -> imitates(HANDLE, brand)).ifPresent(reasons::add);
        reasons.addAll(textReasons(HANDLE, handle));
        suspiciousUrlService.findNamedBrand(displayName).map(brand -> imitates(NAME, brand)).ifPresent(reasons::add);
        reasons.addAll(textReasons(NAME, displayName));
        reasons.addAll(textReasons(BIO, bio));
        buttonTitles.forEach(title -> reasons.addAll(textReasons(BUTTON_TITLE, title)));
        return List.copyOf(reasons);
    }

    private static String imitates(final String field, final String brand) {
        return field + " " + SuspiciousUrlService.IMITATES_PREFIX + brand;
    }

    private List<String> textReasons(final String field, final String text) {
        return suspiciousUrlService.findTextReasons(text).stream()
                .map(reason -> field + " " + (SuspiciousUrlService.LOGIN_LURE.equals(reason) ? HAS_A + reason : reason))
                .toList();
    }

}
