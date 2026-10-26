package cc.hrva.urlshortener.dto.bio;

import cc.hrva.urlshortener.model.enums.BioLinkState;
import java.time.LocalDate;
import java.util.List;

public record BioPageAnalyticsResponse(int days, LocalDate from, LocalDate to, String handle, long views, long botViews,
                                       List<BioViewDay> daily, List<ButtonClicks> buttons) {

    public record ButtonClicks(Long buttonId, Long urlId, String shortUrl, String title, boolean visible, BioLinkState linkState,
                               boolean shownOnPage, long fromHrvaPages, long allClicks) {
    }

}
