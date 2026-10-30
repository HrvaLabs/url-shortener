package cc.hrva.urlshortener.service.stats;

import cc.hrva.urlshortener.dto.AnalyticsDay;
import cc.hrva.urlshortener.dto.AnalyticsTotals;
import cc.hrva.urlshortener.dto.BreakdownEntry;
import cc.hrva.urlshortener.dto.UrlAnalyticsDto.Breakdowns;
import cc.hrva.urlshortener.dto.stats.TrafficStatsResponse.BioPageViewTotals;
import cc.hrva.urlshortener.dto.stats.TrafficStatsResponse.TopLink;
import cc.hrva.urlshortener.repository.stats.TrafficStatsRepository;
import cc.hrva.urlshortener.repository.stats.TrafficStatsRepository.OwnershipClicks;
import cc.hrva.urlshortener.repository.stats.TrafficStatsRepository.TopLinkRow;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static cc.hrva.urlshortener.model.enums.StatDimension.REFERRER;
import static cc.hrva.urlshortener.model.enums.StatDimension.SOURCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrafficStatsTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 20);
    private static final LocalDate TO = LocalDate.of(2026, 10, 26);
    private static final StatsWindow WINDOW =
            new StatsWindow(7, FROM, TO, LocalDateTime.of(2026, 10, 19, 22, 0), LocalDateTime.of(2026, 10, 26, 23, 0));

    @Mock
    private TrafficStatsRepository repository;

    private TrafficStats trafficStats;

    @BeforeEach
    void setUp() {
        this.trafficStats = new TrafficStats(repository);
    }

    @Test
    void zeroFillsAndTotalsTheWindow() {
        when(repository.findDaily(FROM, TO)).thenReturn(List.of(
                new AnalyticsDay(LocalDate.of(2026, 10, 21), 4, 3, 1),
                new AnalyticsDay(TO, 6, 5, 2)));
        givenOwnership(2, 8);
        when(repository.sumAllTimeVisits()).thenReturn(41200L);

        final var traffic = trafficStats.collect(WINDOW);

        assertThat(traffic.days()).isEqualTo(7);
        assertThat(traffic.from()).isEqualTo(FROM);
        assertThat(traffic.to()).isEqualTo(TO);
        assertThat(traffic.daily()).containsExactly(
                new AnalyticsDay(FROM, 0, 0, 0),
                new AnalyticsDay(LocalDate.of(2026, 10, 21), 4, 3, 1),
                new AnalyticsDay(LocalDate.of(2026, 10, 22), 0, 0, 0),
                new AnalyticsDay(LocalDate.of(2026, 10, 23), 0, 0, 0),
                new AnalyticsDay(LocalDate.of(2026, 10, 24), 0, 0, 0),
                new AnalyticsDay(LocalDate.of(2026, 10, 25), 0, 0, 0),
                new AnalyticsDay(TO, 6, 5, 2));
        assertThat(traffic.totals()).isEqualTo(new AnalyticsTotals(10, 8, 3));
        assertThat(traffic.anonymousClicks()).isEqualTo(2);
        assertThat(traffic.accountClicks()).isEqualTo(8);
        assertThat(traffic.allTimeVisits()).isEqualTo(41200);
    }

    @Test
    void topLinksCarryTheHostAndOwner() {
        when(repository.findTopLinks(FROM, TO, 10)).thenReturn(List.of(
                new TopLinkRow(7L, "menu", "https://shop.example.com/a?b=c", "ana@example.com", 120, 95, "go.example.com"),
                new TopLinkRow(9L, "flyer", "https://example.org/x", null, 30, 20, null),
                new TopLinkRow(11L, "odd", "", null, 5, 5, null)));
        givenOwnership(35, 120);

        final var traffic = trafficStats.collect(WINDOW);

        assertThat(traffic.topLinks()).containsExactly(
                new TopLink(7L, "menu", "shop.example.com", "ana@example.com", 120, 95, "go.example.com"),
                new TopLink(9L, "flyer", "example.org", null, 30, 20, null),
                new TopLink(11L, "odd", "", null, 5, 5, null));
    }

    @Test
    void breakdownsKeepTheTop10PlusOther() {
        final var referrers = new ArrayList<BreakdownEntry>();
        IntStream.rangeClosed(1, 7).forEach(index -> referrers.add(new BreakdownEntry("r" + index, 13 - index)));
        referrers.add(new BreakdownEntry("other", 5));
        IntStream.rangeClosed(8, 12).forEach(index -> referrers.add(new BreakdownEntry("r" + index, 13 - index)));
        when(repository.findBreakdownTotals(FROM, TO)).thenReturn(Map.of(REFERRER, referrers));
        givenOwnership(0, 0);

        final var breakdowns = trafficStats.collect(WINDOW).breakdowns();

        final var expected = new ArrayList<BreakdownEntry>();
        IntStream.rangeClosed(1, 10).forEach(index -> expected.add(new BreakdownEntry("r" + index, 13 - index)));
        expected.add(new BreakdownEntry("other", 8));
        assertThat(breakdowns.referrers()).containsExactlyElementsOf(expected);
        assertThat(breakdowns.countries()).isEmpty();
        assertThat(breakdowns.devices()).isEmpty();
        assertThat(breakdowns.browsers()).isEmpty();
        assertThat(breakdowns.operatingSystems()).isEmpty();
        assertThat(breakdowns.sources()).isEmpty();
    }

    @Test
    void sourcesComeThroughTheBreakdowns() {
        when(repository.findBreakdownTotals(FROM, TO)).thenReturn(Map.of(SOURCE, List.of(
                new BreakdownEntry("direct", 7), new BreakdownEntry("qr", 3))));
        givenOwnership(0, 0);

        final var breakdowns = trafficStats.collect(WINDOW).breakdowns();

        assertThat(breakdowns.sources()).containsExactly(new BreakdownEntry("direct", 7), new BreakdownEntry("qr", 3));
    }

    @Test
    void bioPageViewsAreSummedOverTheWindow() {
        givenOwnership(0, 0);
        when(repository.sumBioPageViews(FROM, TO)).thenReturn(new BioPageViewTotals(40, 6));

        assertThat(trafficStats.collect(WINDOW).bioPageViews()).isEqualTo(new BioPageViewTotals(40, 6));
    }

    @Test
    void emptySiteIsAllZeros() {
        givenOwnership(0, 0);
        when(repository.sumBioPageViews(FROM, TO)).thenReturn(new BioPageViewTotals(0, 0));

        final var traffic = trafficStats.collect(WINDOW);

        assertThat(traffic.daily()).containsExactlyElementsOf(FROM.datesUntil(TO.plusDays(1))
                .map(date -> new AnalyticsDay(date, 0, 0, 0))
                .toList());
        assertThat(traffic.daily()).hasSize(7);
        assertThat(traffic.totals()).isEqualTo(new AnalyticsTotals(0, 0, 0));
        assertThat(traffic.anonymousClicks()).isZero();
        assertThat(traffic.accountClicks()).isZero();
        assertThat(traffic.topLinks()).isEmpty();
        assertThat(traffic.breakdowns()).isEqualTo(new Breakdowns(List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        assertThat(traffic.allTimeVisits()).isZero();
        assertThat(traffic.bioPageViews()).isEqualTo(new BioPageViewTotals(0, 0));
        verify(repository).findDaily(FROM, TO);
        verify(repository).findClicksByOwnership(FROM, TO);
        verify(repository).findTopLinks(FROM, TO, 10);
        verify(repository).findBreakdownTotals(FROM, TO);
        verify(repository).sumAllTimeVisits();
        verify(repository).sumBioPageViews(FROM, TO);
    }

    private void givenOwnership(final long anonymous, final long account) {
        when(repository.findClicksByOwnership(FROM, TO)).thenReturn(new OwnershipClicks(anonymous, account));
    }

}
