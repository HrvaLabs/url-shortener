package cc.hrva.urlshortener.service.abuse;

import cc.hrva.urlshortener.dto.BreakdownEntry;
import cc.hrva.urlshortener.service.SettingsService;
import cc.hrva.urlshortener.service.abuse.AbuseReportComposer.Domain;
import cc.hrva.urlshortener.service.abuse.AbuseReportComposer.Link;
import cc.hrva.urlshortener.service.settings.SettingKey;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class AbuseReportComposerTest {

    private static final List<String> CAMPAIGN_DOMAINS = List.of("buokpky.com", "dgjetnv.com", "dlsetyk.com", "dsdckkf.com", "ebnccxh.com",
            "eikvptb.com", "ezvlwdm.com", "fnjphdp.com", "frywxfu.com", "fvvxctw.com", "gofhwjf.com", "hsbpqwj.com", "ivwanbx.com", "jehenmy.com",
            "jvbvmdl.com", "klveggf.com", "lsppreo.com", "mitrpmd.com", "nbmgpmx.com", "nbycfou.com", "npqymns.com", "nubksat.com", "omaehhm.com",
            "onmtonj.com", "pqqolce.com", "qctwqfc.com", "rnrlfac.com", "sccjlej.com", "sjcsvbl.com", "sssplgd.com", "tklmiyc.com", "upofrnh.com",
            "vxokzce.com", "wlkybdd.com", "wvttzqr.com", "xdvqnxj.com", "xiwqhzv.com", "xlsntlg.com", "yhnivjb.com");
    private static final LocalDate REGISTERED = LocalDate.of(2026, 8, 14);
    private static final LocalDate FIRST_DAY = LocalDate.of(2026, 10, 28);
    private static final LocalDate SECOND_DAY = LocalDate.of(2026, 10, 29);
    private static final String PHISHING_PAGE = "https://wwkn0gb.bgp37.sbs/?r=plfN8D&t=1793210826";
    private static final Map<String, String> TOP_LINKS = Map.of(
            "omaehhm.com", "https://ll.omaehhm.com/wqamhcsom/?r=plfN8D&t=1793210826",
            "buokpky.com", "https://qe.buokpky.com/wqamhcsom/?r=Xk2pQa&t=1793210911",
            "dgjetnv.com", "https://mz.dgjetnv.com/wqamhcsom/?r=Tn8wLc&t=1793211002");
    private static final Map<String, Long> TOP_CLICKS = Map.of("omaehhm.com", 300L, "buokpky.com", 200L, "dgjetnv.com", 100L, "yhnivjb.com", 20L);

    @Mock
    private SettingsService settingsService;

    private AbuseReportComposer composer;

    @BeforeEach
    void setUp() {
        lenient().when(settingsService.text(SettingKey.ABUSE_REPORTER_NAME)).thenReturn("Karlo Hrvacic Test");
        lenient().when(settingsService.addresses(SettingKey.ABUSE_REPORTER_ADDRESS)).thenReturn(List.of("abuse@hrva.cc"));
        composer = new AbuseReportComposer(settingsService);
    }

    @Test
    void theCampaignsNameSiloReportMatchesTheExpectedEmail() {
        final var links = new ArrayList<Link>();
        for (int index = 0; index < CAMPAIGN_DOMAINS.size(); index++) {
            final var domain = CAMPAIGN_DOMAINS.get(index);
            final var longUrl = TOP_LINKS.getOrDefault(domain, "https://x" + index + "." + domain + "/wqamhcsom/?r=c" + index + "&t=1793210826");
            final var finalUrl = switch (index % 3) {
                case 0 -> PHISHING_PAGE;
                case 1 -> null;
                default -> "https://x" + index + "." + domain + "/wqamhcsom/";
            };
            links.add(new Link(index, longUrl, finalUrl, index < 20 ? FIRST_DAY : SECOND_DAY, false, TOP_CLICKS.getOrDefault(domain, 10L)));
        }
        final var domains = CAMPAIGN_DOMAINS.reversed().stream().map(domain -> new Domain(domain, REGISTERED)).toList();

        final var email = composer.compose("NameSilo, LLC", domains, links, List.of(new BreakdownEntry("PT", 558), new BreakdownEntry("ES", 330)));

        assertThat(email.subject() + "\n\n" + email.body() + "\n").isEqualTo(expected());
    }

    @Test
    void oneDomainSaysDomain() {
        final var email = composer.compose("NameSilo, LLC", List.of(new Domain("omaehhm.com", null)),
                List.of(new Link(1, "https://ll.omaehhm.com/a", PHISHING_PAGE, FIRST_DAY, false, 1)), List.of());

        assertThat(email.subject()).isEqualTo("Phishing: 1 domain used in links to wwkn0gb.bgp37.sbs");
        assertThat(email.body()).contains("  omaehhm.com (registration date unknown)\n")
                .contains("These links had 1 click on hrva.cc. The links are turned off on hrva.cc.");
    }

    @Test
    void noFinalHostSaysAPhishingPage() {
        final var email = composer.compose("NameSilo, LLC", List.of(new Domain("omaehhm.com", REGISTERED), new Domain("buokpky.com", REGISTERED)),
                List.of(new Link(1, "https://ll.omaehhm.com/a", null, FIRST_DAY, false, 0),
                        new Link(2, "https://qe.buokpky.com/a", "https://qe.buokpky.com/a/", FIRST_DAY, false, 0),
                        new Link(3, "https://omaehhm.com/b", "https://www.omaehhm.com/b", FIRST_DAY, false, 0)), List.of());

        assertThat(email.subject()).isEqualTo("Phishing: 2 domains used in links to a phishing page");
        assertThat(email.body()).contains("without an account that lead to a phishing page. They used these domains, registered with NameSilo, LLC:\n"
                + "  buokpky.com (registered 14 August 2026)\n  omaehhm.com (registered 14 August 2026)\n");
    }

    @Test
    void theMostCommonFinalHostWinsAndTiesGoAlphabetically() {
        final var email = composer.compose("NameSilo, LLC", List.of(new Domain("omaehhm.com", REGISTERED)),
                List.of(new Link(1, "https://a.omaehhm.com/", "https://zeta.example/x", FIRST_DAY, false, 0),
                        new Link(2, "https://b.omaehhm.com/", "https://alpha.example/y", FIRST_DAY, false, 0),
                        new Link(3, "https://c.omaehhm.com/", "not a url", FIRST_DAY, false, 0)), List.of());

        assertThat(email.subject()).isEqualTo("Phishing: 1 domain used in links to alpha.example");
    }

    @Test
    void someLinksStillOnSaysSo() {
        final var email = composer.compose("NameSilo, LLC", List.of(new Domain("omaehhm.com", REGISTERED)),
                List.of(new Link(1, "https://a.omaehhm.com/", null, FIRST_DAY, true, 2_000),
                        new Link(2, "https://b.omaehhm.com/", null, FIRST_DAY, true, 5),
                        new Link(3, "https://c.omaehhm.com/", null, FIRST_DAY, false, 0)),
                List.of(new BreakdownEntry("PT", 1_500), new BreakdownEntry("ES", 400), new BreakdownEntry("BR", 105)));

        assertThat(email.body()).contains("These links had 2,005 clicks on hrva.cc, mostly from Portugal (1,500), Spain (400) and Brazil (105). "
                + "2 of the 3 links are still on; I'm turning them off.\n");
    }

    @Test
    void aSingleDaySaysOn() {
        final var email = composer.compose("NameSilo, LLC", List.of(new Domain("omaehhm.com", REGISTERED)),
                List.of(new Link(1, "https://a.omaehhm.com/", null, FIRST_DAY, false, 0), new Link(2, "https://b.omaehhm.com/", null, FIRST_DAY, false, 0)),
                List.of(new BreakdownEntry("XX", 3), new BreakdownEntry("PT", 2)));

        assertThat(email.body()).startsWith("Hello,\n\nI run hrva.cc, a free URL shortener. On 28 October 2026, short links were made on hrva.cc ")
                .contains("These links had 0 clicks on hrva.cc, mostly from Portugal (2).")
                .endsWith("Please suspend these domains under your abuse policy.\n\nKarlo Hrvacic Test\nhrva.cc, abuse@hrva.cc");
    }

    @Test
    void exampleDestinationsAreTheThreeMostClickedDistinctOnes() {
        final var email = composer.compose("NameSilo, LLC", List.of(new Domain("omaehhm.com", REGISTERED)),
                List.of(new Link(4, "https://d.omaehhm.com/", null, FIRST_DAY, false, 1),
                        new Link(1, "https://a.omaehhm.com/", null, FIRST_DAY, false, 9),
                        new Link(5, "https://a.omaehhm.com/", null, FIRST_DAY, false, 8),
                        new Link(3, "https://c.omaehhm.com/", null, FIRST_DAY, false, 1),
                        new Link(2, "https://b.omaehhm.com/", null, FIRST_DAY, false, 0)), List.of());

        assertThat(email.body()).contains("Example destinations:\n  https://a.omaehhm.com/\n  https://c.omaehhm.com/\n  https://d.omaehhm.com/\n\n");
    }

    private static String expected() {
        try (var input = AbuseReportComposerTest.class.getResourceAsStream("/abuse/expected-namesilo.txt")) {
            return new String(input.readAllBytes(), StandardCharsets.US_ASCII);
        } catch (final IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

}
