package cc.hrva.urlshortener.dto.bio;

import java.time.LocalDate;
import java.util.List;

public record BioPageAnalyticsResponse(int days, LocalDate from, LocalDate to, String handle, long views, long botViews,
                                       List<BioViewDay> daily, List<ButtonClicks> buttons) {

    public record ButtonClicks(Long buttonId, Long urlId, String shortUrl, String title, boolean shownOnPage, long fromHrvaPages,
                               long allClicks) {
    }

}
