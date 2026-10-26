package cc.hrva.urlshortener.dto.bio;

import java.time.LocalDate;

public record BioViewDay(LocalDate date, long views, long botViews) {
}
