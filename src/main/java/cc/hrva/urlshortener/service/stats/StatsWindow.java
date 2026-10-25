package cc.hrva.urlshortener.service.stats;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record StatsWindow(int days, LocalDate from, LocalDate to, LocalDateTime start, LocalDateTime end) {
}
