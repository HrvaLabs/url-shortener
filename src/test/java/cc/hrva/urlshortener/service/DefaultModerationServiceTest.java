package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.DomainRuleDto;
import cc.hrva.urlshortener.dto.DomainRuleResponse;
import cc.hrva.urlshortener.dto.LinkDestination;
import cc.hrva.urlshortener.dto.LinkVerdict;
import cc.hrva.urlshortener.dto.LinkVerdict.Outcome;
import cc.hrva.urlshortener.dto.ReviewBulkResolveResponse;
import cc.hrva.urlshortener.exception.DomainRuleNotFoundException;
import cc.hrva.urlshortener.exception.UrlNotFoundException;
import cc.hrva.urlshortener.exception.UrlValidationException;
import cc.hrva.urlshortener.exception.UserNotFoundException;
import cc.hrva.urlshortener.model.DomainRule;
import cc.hrva.urlshortener.model.LinkReview;
import cc.hrva.urlshortener.model.Url;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.AuditAction;
import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.DomainRuleType;
import cc.hrva.urlshortener.model.enums.ReviewAction;
import cc.hrva.urlshortener.model.enums.ReviewResolution;
import cc.hrva.urlshortener.repository.DomainRuleRepository;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import cc.hrva.urlshortener.service.alert.AdminAlertEvent;
import cc.hrva.urlshortener.service.alert.AlertedLink;
import cc.hrva.urlshortener.service.blocklist.BlocklistConfigurationChangedEvent;
import cc.hrva.urlshortener.service.impl.DefaultModerationService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultModerationServiceTest {

    private static final String ADMIN = "admin@hrva.cc";
    private static final String NOT_A_DOMAIN = "Enter a domain such as example.com, not a top-level domain or an IP address.";
    private static final LocalDateTime BLOCKED_AT = LocalDateTime.of(2026, 10, 20, 2, 0);
    private static final LocalDateTime RULE_CREATED_AT = LocalDateTime.of(2026, 10, 1, 9, 0);
    private static final LinkVerdict ALLOWED = new LinkVerdict(Outcome.ALLOWED, List.of("allowed domain jofogas.cards"), null, null);

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private DomainRuleRepository domainRuleRepository;

    @Mock
    private LinkCheckService linkCheckService;

    @Mock
    private ReviewQueueService reviewQueueService;

    @Mock
    private UserService userService;

    @Mock
    private SendingEmailService sendingEmailService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private IPAddressService ipAddressService;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache cache;

    @Mock
    private EntityManager entityManager;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ScammerSignalService scammerSignalService;

    @Mock
    private TaskScheduler taskScheduler;

    private final Map<Long, Url> links = new TreeMap<>();
    private final Map<String, DomainRule> rules = new HashMap<>();
    private final List<List<Long>> savedPerTransaction = new ArrayList<>();
    private final List<String> outOfPlace = new ArrayList<>();
    private boolean inTransaction;

    private ModerationService moderationService;

    @BeforeEach
    void setUp() {
        lenient().when(urlRepository.findById(anyLong())).thenAnswer(invocation -> Optional.ofNullable(links.get(invocation.<Long>getArgument(0))));
        lenient().when(urlRepository.save(any(Url.class))).thenAnswer(invocation -> {
            final Url url = invocation.getArgument(0);
            if (inTransaction) {
                savedPerTransaction.getLast().add(url.getId());
            } else {
                outOfPlace.add("link " + url.getId() + " saved outside a transaction");
            }
            return url;
        });
        lenient().when(urlRepository.existsUrlByShortUrlAndActiveTrue(anyString())).thenAnswer(invocation ->
                links.values().stream().anyMatch(url -> url.isActive() && url.getShortUrl().equals(invocation.getArgument(0))));
        lenient().when(urlRepository.findActiveDestinationsAfter(anyLong(), any(Pageable.class)))
                .thenAnswer(invocation -> page(invocation.getArgument(0), invocation.getArgument(1), Url::isActive));
        lenient().when(urlRepository.findBlockedDestinationsAfter(anyCollection(), anyLong(), any(Pageable.class))).thenAnswer(invocation -> {
            final Collection<BlockReason> reasons = invocation.getArgument(0);
            return page(invocation.getArgument(1), invocation.getArgument(2), url -> !url.isActive() && reasons.contains(url.getBlockReason()));
        });
        lenient().when(urlRepository.findActiveIdsByOwner(any(User.class)))
                .thenAnswer(invocation -> ids(url -> url.isActive() && ownedBy(url, invocation.getArgument(0))));
        lenient().when(urlRepository.findIdsByOwnerAndBlockReason(any(User.class), any(BlockReason.class)))
                .thenAnswer(invocation -> ids(url -> ownedBy(url, invocation.getArgument(0)) && url.getBlockReason() == invocation.getArgument(1)));
        lenient().when(domainRuleRepository.findByDomain(anyString())).thenAnswer(invocation -> Optional.ofNullable(rules.get(invocation.<String>getArgument(0))));
        lenient().when(domainRuleRepository.save(any(DomainRule.class))).thenAnswer(invocation -> {
            final DomainRule rule = invocation.getArgument(0);
            rules.put(rule.getDomain(), rule);
            return rule;
        });
        lenient().when(reviewQueueService.open(any(Pageable.class), isNull())).thenReturn(Page.empty());
        lenient().doAnswer(invocation -> {
            if (!inTransaction) {
                outOfPlace.add("review of link " + invocation.<Url>getArgument(0).getId() + " resolved outside a transaction");
            }
            return null;
        }).when(reviewQueueService).resolveOpen(any(Url.class), any(ReviewResolution.class), anyString());
        lenient().doAnswer(invocation -> {
            if (inTransaction) {
                outOfPlace.add("possible scammers refreshed before the change committed");
            }
            return null;
        }).when(scammerSignalService).invalidate();
        lenient().when(cacheManager.getCache("urls")).thenReturn(cache);
        lenient().doAnswer(invocation -> {
            if (inTransaction) {
                outOfPlace.add(invocation.getArgument(0) + " evicted before its transaction committed");
            }
            return null;
        }).when(cache).evict(any());
        lenient().doAnswer(invocation -> {
            if (inTransaction) {
                outOfPlace.add("link " + invocation.<Url>getArgument(0).getId() + " detached before its transaction committed");
            }
            return null;
        }).when(entityManager).detach(any());
        moderationService = new DefaultModerationService(urlRepository, userRepository, domainRuleRepository, linkCheckService,
                reviewQueueService, userService, sendingEmailService, auditLogService, ipAddressService, cacheManager, eventPublisher, recordingTransactions(),
                entityManager, scammerSignalService, taskScheduler);
    }

    @Test
    void blockingADomainBlocksItAndItsSubdomainsButNotAHostThatOnlyStartsWithIt() {
        final var alice = user(1L, "alice@example.com");
        final var bob = user(2L, "bob@example.com");
        final var apex = link(10L, "https://jofogas.cards/login", alice);
        final var www = link(11L, "https://www.jofogas.cards/item/7", bob);
        final var lookalike = link(12L, "https://jofogas.cards.example.com/", user(3L, "carol@example.com"));
        final var anonymous = link(13L, "https://JOFOGAS.cards:443/x", null);

        final var blocked = moderationService.blockDomain("jofogas.cards", "phishing wave", ADMIN, "admin", null);

        assertThat(blocked).isEqualTo(3);
        assertThat(List.of(apex, www, anonymous)).allSatisfy(url -> {
            assertThat(url.isActive()).isFalse();
            assertThat(url.getBlockReason()).isEqualTo(BlockReason.DOMAIN_RULE);
            assertThat(url.getBlockDetail()).isEqualTo("jofogas.cards");
            assertThat(url.getBlockedAt()).isNotNull();
            verify(cache).evict(url.getShortUrl());
            verify(reviewQueueService).resolveOpen(url, ReviewResolution.DOMAIN_BLOCKED, ADMIN);
        });
        assertThat(lookalike.isActive()).isTrue();
        assertThat(lookalike.getBlockReason()).isNull();
        verify(cache, never()).evict("s12");
        verify(sendingEmailService).sendEmailUrlBlocked(alice, apex);
        verify(sendingEmailService).sendEmailUrlBlocked(bob, www);
        verifyNoMoreInteractions(sendingEmailService);
        assertThat(rules.get("jofogas.cards"))
                .extracting(DomainRule::getType, DomainRule::getReason, DomainRule::getCreatedBy)
                .containsExactly(DomainRuleType.BLOCK, "phishing wave", ADMIN);
        verify(auditLogService).log(ADMIN, AuditAction.BLOCK_DOMAIN, "Domain", "jofogas.cards", "reason: phishing wave, links: 3, via: admin");
        verifyNoMoreInteractions(auditLogService);
        assertThat(outOfPlace).isEmpty();
    }

    @Test
    void eachLinkIsBlockedInItsOwnTransaction() {
        link(10L, "https://jofogas.cards/a", null);
        link(11L, "https://jofogas.cards/b", null);
        link(12L, "https://www.jofogas.cards/c", null);

        moderationService.blockDomain("jofogas.cards", null, ADMIN, "admin", null);

        assertThat(savedPerTransaction).filteredOn(saved -> !saved.isEmpty())
                .containsExactly(List.of(10L), List.of(11L), List.of(12L));
        assertThat(outOfPlace).isEmpty();
    }

    @Test
    void eachChangedLinkLeavesThePersistenceContextOnceItsTransactionCommits() {
        final var first = link(10L, "https://jofogas.cards/a", null);
        final var second = link(11L, "https://jofogas.cards/b", null);
        final var single = link(12L, "https://example.org/", null);
        final var user = user(5L, "alice@example.com");
        user.setActive(false);
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));
        final var restored = blocked(13L, "https://a.example/", BlockReason.USER_BLOCKED, user);

        moderationService.blockDomain("jofogas.cards", null, ADMIN, "admin", null);
        moderationService.blockLink(12L, BlockReason.ADMIN, null, ADMIN, "admin");
        moderationService.reactivateUser(5L, ADMIN, "admin");

        verify(entityManager).detach(first);
        verify(entityManager).detach(second);
        verify(entityManager).detach(single);
        verify(entityManager).detach(restored);
        assertThat(outOfPlace).isEmpty();
    }

    @Test
    void aBlockedLinkIsEvictedAgainTwoSecondsLaterSoARacingClickCannotKeepItCached() {
        link(12L, "https://example.org/", null);
        final var eviction = ArgumentCaptor.forClass(Runnable.class);
        final var evictAt = ArgumentCaptor.forClass(Instant.class);
        final var before = Instant.now();

        moderationService.blockLink(12L, BlockReason.ADMIN, null, ADMIN, "admin");

        verify(taskScheduler).schedule(eviction.capture(), evictAt.capture());
        assertThat(evictAt.getValue()).isBetween(before.plusSeconds(2), Instant.now().plusSeconds(2));
        verify(cache).evict("s12");
        eviction.getValue().run();
        verify(cache, times(2)).evict("s12");
        assertThat(outOfPlace).isEmpty();
    }

    @Test
    void aReactivatedLinkIsEvictedAgainTwoSecondsLater() {
        final var user = user(5L, "alice@example.com");
        user.setActive(false);
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));
        blocked(13L, "https://a.example/", BlockReason.USER_BLOCKED, user);
        final var eviction = ArgumentCaptor.forClass(Runnable.class);
        final var evictAt = ArgumentCaptor.forClass(Instant.class);
        final var before = Instant.now();

        moderationService.reactivateUser(5L, ADMIN, "admin");

        verify(taskScheduler).schedule(eviction.capture(), evictAt.capture());
        assertThat(evictAt.getValue()).isBetween(before.plusSeconds(2), Instant.now().plusSeconds(2));
        eviction.getValue().run();
        verify(cache, times(2)).evict("s13");
    }

    @Test
    void aBlockStillGoesThroughWhenTheSecondEvictionCannotBeScheduled() {
        final var url = link(12L, "https://example.org/", null);
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenThrow(new TaskRejectedException("shutting down"));

        final var response = moderationService.blockLink(12L, BlockReason.ADMIN, null, ADMIN, "admin");

        assertThat(response.active()).isFalse();
        assertThat(url.getBlockReason()).isEqualTo(BlockReason.ADMIN);
        verify(cache).evict("s12");
    }

    @Test
    void blockingADomainTurnsItsAllowRuleIntoABlock() {
        final var existing = rule("jofogas.cards", DomainRuleType.ALLOW);

        moderationService.blockDomain("jofogas.cards", "it was a scam after all", ADMIN, "admin", null);

        assertThat(rules).containsOnlyKeys("jofogas.cards");
        assertThat(rules.get("jofogas.cards")).isSameAs(existing);
        assertThat(existing.getType()).isEqualTo(DomainRuleType.BLOCK);
        assertThat(existing.getReason()).isEqualTo("it was a scam after all");
    }

    @Test
    void aDomainBlockSparesLinksAMoreSpecificAllowStillTrusts() {
        rule("docs.google.com", DomainRuleType.ALLOW);
        rule("evil.docs.google.com", DomainRuleType.BLOCK);
        when(domainRuleRepository.findAll()).thenAnswer(invocation -> List.copyOf(rules.values()));
        final var trusted = link(10L, "https://docs.google.com/forms/d/x", null);
        final var underABlock = link(11L, "https://evil.docs.google.com/login", null);
        final var sites = link(12L, "https://sites.google.com/view/scam", null);
        final var apex = link(13L, "https://google.com/", null);

        final var blocked = moderationService.blockDomain("google.com", null, ADMIN, "mcp", 3);

        assertThat(blocked).isEqualTo(3);
        assertThat(trusted.isActive()).isTrue();
        assertThat(List.of(underABlock, sites, apex)).allSatisfy(url -> assertThat(url.getBlockReason()).isEqualTo(BlockReason.DOMAIN_RULE));
    }

    @Test
    void aBlockFromTheReviewQueueKeepsTheRulesReasonAndWhoCreatedIt() {
        final var existing = rule("jofogas.cards", DomainRuleType.ALLOW);
        link(62L, "https://www.jofogas.cards/login", null);

        moderationService.resolveReview(62L, ReviewAction.BLOCK_DOMAIN, null, ADMIN, "admin");

        assertThat(existing.getType()).isEqualTo(DomainRuleType.BLOCK);
        assertThat(existing)
                .extracting(DomainRule::getReason, DomainRule::getCreatedBy, DomainRule::getCreatedAt)
                .containsExactly("seeded", "system", RULE_CREATED_AT);
    }

    @Test
    void aNewReasonReplacesTheOldOneButTheRuleKeepsItsCreator() {
        final var existing = rule("jofogas.cards", DomainRuleType.ALLOW);

        moderationService.blockDomain("jofogas.cards", "it was a scam after all", ADMIN, "admin", null);

        assertThat(existing)
                .extracting(DomainRule::getReason, DomainRule::getCreatedBy, DomainRule::getCreatedAt)
                .containsExactly("it was a scam after all", "system", RULE_CREATED_AT);
    }

    @Test
    void aCappedDomainBlockRefusesWithoutChangingAnythingWhenMoreLinksWouldBeAffected() {
        link(10L, "https://jofogas.cards/a", user(1L, "alice@example.com"));
        link(11L, "https://jofogas.cards/b", null);
        link(12L, "https://www.jofogas.cards/c", null);

        assertThatThrownBy(() -> moderationService.blockDomain("jofogas.cards", "phishing wave", ADMIN, "mcp", 2))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("This would affect 3 links, more than the 2 allowed here. Use the dashboard.");

        assertThat(links.values()).allSatisfy(url -> assertThat(url.isActive()).isTrue());
        assertThat(rules).isEmpty();
        verify(domainRuleRepository, never()).save(any());
        verify(urlRepository, never()).save(any());
        verifyNoInteractions(sendingEmailService, auditLogService, reviewQueueService, cache);
    }

    @Test
    void aCappedDomainBlockGoesAheadAtExactlyTheCap() {
        link(10L, "https://jofogas.cards/a", null);
        link(11L, "https://jofogas.cards/b", null);

        assertThat(moderationService.blockDomain("jofogas.cards", null, ADMIN, "mcp", 2)).isEqualTo(2);
    }

    @Test
    void aLinkThatFailsToBlockIsLoggedAndTheRestAreStillBlocked() {
        final var owner = user(1L, "alice@example.com");
        link(10L, "https://jofogas.cards/a", owner);
        final var failing = link(11L, "https://jofogas.cards/b", owner);
        final var after = link(12L, "https://jofogas.cards/c", owner);
        doThrow(new DataAccessResourceFailureException("connection reset"))
                .when(urlRepository).save(argThat(url -> url != null && url.getId() == 11L));

        final var blocked = moderationService.blockDomain("jofogas.cards", null, ADMIN, "admin", null);

        assertThat(blocked).isEqualTo(2);
        assertThat(after.getBlockReason()).isEqualTo(BlockReason.DOMAIN_RULE);
        verify(cache, never()).evict("s11");
        verify(sendingEmailService, never()).sendEmailUrlBlocked(owner, failing);
        verify(auditLogService).log(ADMIN, AuditAction.BLOCK_DOMAIN, "Domain", "jofogas.cards", "links: 2, via: admin");
    }

    @Test
    void aDomainBlockReadsTheActiveLinksInPagesOf500ById() {
        LongStream.rangeClosed(1, 1201).forEach(id ->
                link(id, id % 100 == 0 ? "https://jofogas.cards/" + id : "https://site" + id + ".example/", null));

        final var blocked = moderationService.blockDomain("jofogas.cards", null, ADMIN, "admin", null);

        assertThat(blocked).isEqualTo(12);
        final var pages = ArgumentCaptor.forClass(Pageable.class);
        verify(urlRepository, times(3)).findActiveDestinationsAfter(anyLong(), pages.capture());
        verify(urlRepository).findActiveDestinationsAfter(eq(0L), any(Pageable.class));
        verify(urlRepository).findActiveDestinationsAfter(eq(500L), any(Pageable.class));
        verify(urlRepository).findActiveDestinationsAfter(eq(1000L), any(Pageable.class));
        assertThat(pages.getAllValues()).allSatisfy(pageable -> assertThat(pageable.getPageSize()).isEqualTo(500));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"cards", "co.uk", "localhost", "203.0.113.7", "not a domain"})
    void aDomainRuleNeedsADomainBelowAPublicSuffix(final String domain) {
        link(10L, "https://jofogas.cards/a", null);

        assertThatThrownBy(() -> moderationService.blockDomain(domain, null, ADMIN, "admin", null))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage(NOT_A_DOMAIN);
        assertThatThrownBy(() -> moderationService.allowDomain(domain, null, ADMIN, "admin"))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage(NOT_A_DOMAIN);
        verify(domainRuleRepository, never()).save(any());
        verify(urlRepository, never()).save(any());
    }

    @Test
    void theDomainIsNormalisedBeforeTheRuleIsSaved() {
        final var url = link(10L, "https://www.jofogas.cards/a", null);

        moderationService.blockDomain("  JoFogas.Cards. ", null, ADMIN, "admin", null);

        assertThat(rules).containsOnlyKeys("jofogas.cards");
        assertThat(url.getBlockDetail()).isEqualTo("jofogas.cards");
    }

    @Test
    void trustingADomainReplacesItsBlockResolvesItsReviewsAndRestoresListAndRuleBlocks() {
        final var existing = rule("jofogas.cards", DomainRuleType.BLOCK);
        final var owner = user(1L, "alice@example.com");
        final var listed = blocked(20L, "https://jofogas.cards/a", BlockReason.BLOCKLIST, owner);
        final var scam = blocked(21L, "https://www.jofogas.cards/b", BlockReason.SCAM_SIGNALS, owner);
        final var ruled = blocked(22L, "https://m.jofogas.cards/c", BlockReason.DOMAIN_RULE, null);
        final var safeBrowsing = blocked(23L, "https://jofogas.cards/d", BlockReason.SAFE_BROWSING, owner);
        final var byAdmin = blocked(24L, "https://jofogas.cards/e", BlockReason.ADMIN, owner);
        final var userBlocked = blocked(25L, "https://jofogas.cards/f", BlockReason.USER_BLOCKED, owner);
        final var elsewhere = blocked(26L, "https://jofogas.cards.example.com/", BlockReason.BLOCKLIST, null);
        final var flagged = link(27L, "https://shop.jofogas.cards/", owner);
        final var flaggedElsewhere = link(28L, "https://example.org/", owner);
        when(reviewQueueService.open(any(Pageable.class), isNull())).thenReturn(new PageImpl<>(List.of(review(flagged), review(flaggedElsewhere))));
        when(linkCheckService.check(anyString())).thenReturn(ALLOWED);

        final var restored = moderationService.allowDomain("jofogas.cards", "false positive", ADMIN, "admin");

        assertThat(restored).isEqualTo(3);
        assertThat(rules.get("jofogas.cards")).isSameAs(existing);
        assertThat(existing.getType()).isEqualTo(DomainRuleType.ALLOW);
        assertThat(existing.getReason()).isEqualTo("false positive");
        assertThat(List.of(listed, scam, ruled)).allSatisfy(url -> {
            assertThat(url.isActive()).isTrue();
            assertThat(url.getBlockReason()).isNull();
            assertThat(url.getBlockDetail()).isNull();
            assertThat(url.getBlockedAt()).isNull();
            verify(cache).evict(url.getShortUrl());
        });
        assertThat(List.of(safeBrowsing, byAdmin, userBlocked, elsewhere)).allSatisfy(url -> {
            assertThat(url.isActive()).isFalse();
            assertThat(url.getBlockedAt()).isEqualTo(BLOCKED_AT);
            verify(linkCheckService, never()).check(url.getLongUrl());
            verify(cache, never()).evict(url.getShortUrl());
        });
        assertThat(safeBrowsing.getBlockReason()).isEqualTo(BlockReason.SAFE_BROWSING);
        assertThat(byAdmin.getBlockReason()).isEqualTo(BlockReason.ADMIN);
        verify(reviewQueueService).resolveOpen(flagged, ReviewResolution.DOMAIN_TRUSTED, ADMIN);
        verify(reviewQueueService, never()).resolveOpen(eq(flaggedElsewhere), any(), any());
        verify(auditLogService).log(ADMIN, AuditAction.ALLOW_DOMAIN, "Domain", "jofogas.cards", "reason: false positive, reviews: 1, links: 3, via: admin");
        verifyNoMoreInteractions(auditLogService);
        verifyNoInteractions(sendingEmailService);
        assertThat(outOfPlace).isEmpty();
    }

    @Test
    void aLinkTheCheckStillBlocksAfterTheAllowIsSavedStaysBlocked() {
        final var exactLink = blocked(20L, "https://jofogas.cards/phish", BlockReason.BLOCKLIST, null);
        final var restored = blocked(21L, "https://jofogas.cards/fine", BlockReason.BLOCKLIST, null);
        when(linkCheckService.check("https://jofogas.cards/phish")).thenReturn(new LinkVerdict(
                Outcome.BLOCK, List.of("allowed domain jofogas.cards", "on list URLhaus"), BlockReason.BLOCKLIST, "URLhaus"));
        when(linkCheckService.check("https://jofogas.cards/fine")).thenReturn(ALLOWED);

        moderationService.allowDomain("jofogas.cards", null, ADMIN, "admin");

        assertThat(exactLink.isActive()).isFalse();
        assertThat(exactLink.getBlockReason()).isEqualTo(BlockReason.BLOCKLIST);
        assertThat(exactLink.getBlockedAt()).isEqualTo(BLOCKED_AT);
        verify(cache, never()).evict("s20");
        assertThat(restored.isActive()).isTrue();
        final var order = inOrder(domainRuleRepository, linkCheckService);
        order.verify(domainRuleRepository).save(any(DomainRule.class));
        order.verify(linkCheckService).check("https://jofogas.cards/phish");
        verify(auditLogService).log(ADMIN, AuditAction.ALLOW_DOMAIN, "Domain", "jofogas.cards", "reviews: 0, links: 1, via: admin");
    }

    @Test
    void blockingALinkDisablesItEvictsItTellsTheOwnerAndClosesItsReview() {
        final var owner = user(1L, "alice@example.com");
        final var url = link(30L, "https://example.org/x", owner);

        final var response = moderationService.blockLink(30L, BlockReason.ADMIN, "reported by a visitor", ADMIN, "admin");

        assertThat(response.status()).isEqualTo("BLOCKED");
        assertThat(response.active()).isFalse();
        assertThat(url.getBlockReason()).isEqualTo(BlockReason.ADMIN);
        assertThat(url.getBlockDetail()).isEqualTo("reported by a visitor");
        assertThat(url.getBlockedAt()).isNotNull();
        verify(cache).evict("s30");
        verify(reviewQueueService).resolveOpen(url, ReviewResolution.LINK_BLOCKED, ADMIN);
        verify(sendingEmailService).sendEmailUrlBlocked(owner, url);
        verifyNoMoreInteractions(sendingEmailService);
        verify(auditLogService).log(ADMIN, AuditAction.BLOCK_LINK, "Url", "30", "reason: ADMIN, detail: reported by a visitor, links: 1, via: admin");
        assertThat(outOfPlace).isEmpty();
    }

    @Test
    void aBlockDetailLongerThanItsColumnIsShortenedSoTheBlockStillGoesThrough() {
        final var url = link(30L, "https://example.org/x", null);
        final var detail = "reported by a visitor: " + "x".repeat(300);

        final var response = moderationService.blockLink(30L, BlockReason.ADMIN, detail, ADMIN, "mcp");

        assertThat(response.active()).isFalse();
        assertThat(url.getBlockDetail()).hasSize(255).startsWith("reported by a visitor: xxx").endsWith("...");
        verify(auditLogService).log(ADMIN, AuditAction.BLOCK_LINK, "Url", "30", "reason: ADMIN, detail: " + detail + ", links: 1, via: mcp");
    }

    @Test
    void aRuleReasonLongerThanItsColumnIsShortenedSoTheRuleStillSaves() {
        final var reason = "phishing wave: " + "y".repeat(300);

        moderationService.blockDomain("jofogas.cards", reason, ADMIN, "mcp", null);
        moderationService.allowDomain("example.org", reason, ADMIN, "mcp");

        assertThat(rules.values()).allSatisfy(rule ->
                assertThat(rule.getReason()).hasSize(255).startsWith("phishing wave: yyy").endsWith("..."));
        assertThat(rules).containsOnlyKeys("jofogas.cards", "example.org");
    }

    @Test
    void aSafeBrowsingBlockTellsTheOwnerWithTheMalwareEmail() {
        final var owner = user(1L, "alice@example.com");
        final var url = link(30L, "https://example.org/x", owner);

        moderationService.blockLink(30L, BlockReason.SAFE_BROWSING, "SOCIAL_ENGINEERING", "system", "nightly");

        verify(sendingEmailService).sendEmailUrlMalwareDetected(owner, url, "SOCIAL_ENGINEERING");
        verifyNoMoreInteractions(sendingEmailService);
    }

    @Test
    void aSafeBrowsingBlockMarksTheLinkAsMalwareInTheSameTransaction() {
        final var url = link(30L, "https://example.org/x", user(1L, "alice@example.com"));

        moderationService.blockLink(30L, BlockReason.SAFE_BROWSING, "https://example.org/x", "system", "nightly");

        assertThat(url.isActive()).isFalse();
        assertThat(url.getBlockReason()).isEqualTo(BlockReason.SAFE_BROWSING);
        assertThat(url.getThreatType()).isEqualTo(cc.hrva.urlshortener.model.enums.ThreatType.MALWARE);
        assertThat(url.getBlockDetail()).isEqualTo("https://example.org/x");
        assertThat(url.getBlockedAt()).isNotNull();
        assertThat(savedPerTransaction).filteredOn(saved -> !saved.isEmpty()).containsExactly(List.of(30L));
        assertThat(outOfPlace).isEmpty();
    }

    @Test
    void onlyASafeBrowsingBlockSetsAThreatType() {
        final var url = link(30L, "https://example.org/x", user(1L, "alice@example.com"));

        moderationService.blockLink(30L, BlockReason.BLOCKLIST, "URLhaus", "system", "nightly");

        assertThat(url.getBlockReason()).isEqualTo(BlockReason.BLOCKLIST);
        assertThat(url.getThreatType()).isNull();
    }

    @Test
    void blockingAnAlreadyBlockedLinkChangesNothingAndSendsNoSecondEmail() {
        final var owner = user(1L, "alice@example.com");
        final var url = blocked(30L, "https://example.org/x", BlockReason.BLOCKLIST, owner);

        final var response = moderationService.blockLink(30L, BlockReason.ADMIN, "again", ADMIN, "admin");

        assertThat(response.status()).isEqualTo("BLOCKED");
        assertThat(url.getBlockReason()).isEqualTo(BlockReason.BLOCKLIST);
        assertThat(url.getBlockDetail()).isEqualTo("URLhaus");
        assertThat(url.getBlockedAt()).isEqualTo(BLOCKED_AT);
        verify(urlRepository, never()).save(any());
        verifyNoInteractions(sendingEmailService, cache);
        verify(auditLogService).log(ADMIN, AuditAction.BLOCK_LINK, "Url", "30", "reason: ADMIN, detail: again, links: 0, via: admin");
    }

    @Test
    void aLinkBlockedAsMalwareBeforeBlockReasonsExistedCountsAsBlocked() {
        final var url = link(30L, "https://example.org/x", user(1L, "alice@example.com"));
        url.setActive(false);
        url.setThreatType(cc.hrva.urlshortener.model.enums.ThreatType.MALWARE);

        moderationService.blockLink(30L, BlockReason.BLOCKLIST, "URLhaus", "system", "click");

        assertThat(url.getBlockReason()).isNull();
        verify(urlRepository, never()).save(any());
        verifyNoInteractions(sendingEmailService);
    }

    @Test
    void blockingALinkTheOwnerSwitchedOffStillMarksItBlocked() {
        final var url = link(30L, "https://example.org/x", user(1L, "alice@example.com"));
        url.setActive(false);

        moderationService.blockLink(30L, BlockReason.ADMIN, null, ADMIN, "admin");

        assertThat(url.getBlockReason()).isEqualTo(BlockReason.ADMIN);
        assertThat(url.getBlockedAt()).isNotNull();
        verify(auditLogService).log(ADMIN, AuditAction.BLOCK_LINK, "Url", "30", "reason: ADMIN, links: 1, via: admin");
    }

    @Test
    void anAnonymousLinkIsBlockedWithoutAnEmail() {
        final var url = link(30L, "https://example.org/x", null);

        moderationService.blockLink(30L, BlockReason.BLOCKLIST, "URLhaus", "system", "click");

        assertThat(url.getBlockReason()).isEqualTo(BlockReason.BLOCKLIST);
        verifyNoInteractions(sendingEmailService);
    }

    @Test
    void anAutomaticBlockAlertsTheAdminWithTheDestinationHostAndTheReason() {
        link(30L, "https://login-paypa1.top/verify?account=alice@example.com", null);

        moderationService.blockLink(30L, BlockReason.BLOCKLIST, "HaGeZi TIF medium", "system", "click");

        verify(eventPublisher).publishEvent(new AdminAlertEvent.LinkBlocked(
                new AlertedLink(30L, "s30", "login-paypa1.top", null), BlockReason.BLOCKLIST, "HaGeZi TIF medium"));
    }

    @Test
    void anAutomaticBlockAlertNamesTheOwnerById() {
        link(30L, "https://example.org/x", user(7L, "alice@example.com"));

        moderationService.blockLink(30L, BlockReason.SAFE_BROWSING, "SOCIAL_ENGINEERING", "system", "nightly");

        verify(eventPublisher).publishEvent(new AdminAlertEvent.LinkBlocked(
                new AlertedLink(30L, "s30", "example.org", 7L), BlockReason.SAFE_BROWSING, "SOCIAL_ENGINEERING"));
    }

    @Test
    void anAdminBlockingALinkOrADomainFromTheDashboardOrMcpSendsNoAlert() {
        link(30L, "https://example.org/x", null);
        link(31L, "https://example.net/y", user(7L, "alice@example.com"));
        link(32L, "https://jofogas.cards/login", null);
        link(33L, "https://njuskaio.cam/login", null);

        moderationService.blockLink(30L, BlockReason.ADMIN, null, ADMIN, "admin");
        moderationService.blockLink(31L, BlockReason.ADMIN, "phishing", ADMIN, "mcp");
        moderationService.blockDomain("jofogas.cards", "phishing wave", ADMIN, "mcp", 50);
        moderationService.createDomainRule(new DomainRuleDto("njuskaio.cam", DomainRuleType.BLOCK, null), ADMIN, "admin");

        assertThat(links.values()).allSatisfy(url -> assertThat(url.isActive()).isFalse());
        verify(eventPublisher, never()).publishEvent(any(AdminAlertEvent.class));
    }

    @Test
    void anAdminBlockingTheLinkFromTheReviewQueueSendsNoAlert() {
        link(30L, "https://example.org/x", null);

        moderationService.resolveReview(30L, ReviewAction.BLOCK_LINK, null, ADMIN, "admin");

        assertThat(links.get(30L).getBlockReason()).isEqualTo(BlockReason.ADMIN);
        verify(eventPublisher, never()).publishEvent(any(AdminAlertEvent.class));
    }

    @Test
    void onlyTheAutomaticOfTwoBlocksAlerts() {
        link(30L, "https://login-paypa1.top/verify", null);
        link(31L, "https://example.org/x", null);

        moderationService.blockLink(30L, BlockReason.SAFE_BROWSING, "SOCIAL_ENGINEERING", "system", "nightly");
        moderationService.blockLink(31L, BlockReason.ADMIN, null, ADMIN, "admin");

        verify(eventPublisher).publishEvent(new AdminAlertEvent.LinkBlocked(
                new AlertedLink(30L, "s30", "login-paypa1.top", null), BlockReason.SAFE_BROWSING, "SOCIAL_ENGINEERING"));
        verify(eventPublisher, times(1)).publishEvent(any(AdminAlertEvent.class));
    }

    @Test
    void blockingAnAlreadyBlockedLinkSendsNoAlert() {
        blocked(30L, "https://example.org/x", BlockReason.BLOCKLIST, null);

        moderationService.blockLink(30L, BlockReason.ADMIN, "again", ADMIN, "admin");

        verify(eventPublisher, never()).publishEvent(any(AdminAlertEvent.class));
    }

    @Test
    void blockingAUserSendsNoAlertPerLink() {
        final var scammer = user(5L, "scammer@example.com");
        when(userRepository.findById(5L)).thenReturn(Optional.of(scammer));
        link(40L, "https://a.example/", scammer);

        moderationService.blockUser(5L, ADMIN, "admin", null);

        verify(eventPublisher, never()).publishEvent(any(AdminAlertEvent.class));
    }

    @Test
    void blockingAnUnknownLinkIsNotFound() {
        assertThatThrownBy(() -> moderationService.blockLink(404L, BlockReason.ADMIN, null, ADMIN, "admin"))
                .isInstanceOf(UrlNotFoundException.class);
        verifyNoInteractions(auditLogService);
    }

    @Test
    void blockingAUserDeactivatesTheAccountAndBlocksEveryActiveLinkItOwns() {
        final var scammer = user(5L, "scammer@example.com");
        when(userRepository.findById(5L)).thenReturn(Optional.of(scammer));
        final var first = link(40L, "https://a.example/", scammer);
        final var second = link(41L, "https://b.example/", scammer);
        final var switchedOff = link(42L, "https://c.example/", scammer);
        switchedOff.setActive(false);
        final var someoneElses = link(43L, "https://d.example/", user(6L, "bob@example.com"));

        final var blocked = moderationService.blockUser(5L, ADMIN, "admin", null);

        assertThat(blocked).isEqualTo(2);
        verify(userService).deactivate(scammer);
        assertThat(List.of(first, second)).allSatisfy(url -> {
            assertThat(url.isActive()).isFalse();
            assertThat(url.getBlockReason()).isEqualTo(BlockReason.USER_BLOCKED);
            assertThat(url.getBlockedAt()).isNotNull();
            verify(cache).evict(url.getShortUrl());
            verify(reviewQueueService).resolveOpen(url, ReviewResolution.USER_BLOCKED, ADMIN);
        });
        assertThat(switchedOff.getBlockReason()).isNull();
        assertThat(someoneElses.isActive()).isTrue();
        verify(auditLogService).log(ADMIN, AuditAction.BLOCK_USER, "User", "5", "links: 2, via: admin");
        verifyNoMoreInteractions(auditLogService);
        assertThat(outOfPlace).isEmpty();
    }

    @Test
    void blockingAUserWithThreeLinksSendsOnlyTheAccountDeactivatedEmail() {
        final var scammer = user(5L, "scammer@example.com");
        when(userRepository.findById(5L)).thenReturn(Optional.of(scammer));
        doAnswer(invocation -> {
            sendingEmailService.sendEmailAccountDeactivated(invocation.getArgument(0));
            return null;
        }).when(userService).deactivate(scammer);
        link(40L, "https://a.example/", scammer);
        link(41L, "https://b.example/", scammer);
        link(42L, "https://c.example/", scammer);

        assertThat(moderationService.blockUser(5L, ADMIN, "admin", null)).isEqualTo(3);

        verify(sendingEmailService, times(1)).sendEmailAccountDeactivated(scammer);
        verifyNoMoreInteractions(sendingEmailService);
    }

    @Test
    void aCappedUserBlockRefusesWithoutChangingAnythingWhenMoreLinksWouldBeAffected() {
        final var scammer = user(5L, "scammer@example.com");
        when(userRepository.findById(5L)).thenReturn(Optional.of(scammer));
        link(40L, "https://a.example/", scammer);
        link(41L, "https://b.example/", scammer);

        assertThatThrownBy(() -> moderationService.blockUser(5L, ADMIN, "mcp", 1))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("This would affect 2 links, more than the 1 allowed here. Use the dashboard.");

        verifyNoInteractions(userService, sendingEmailService, auditLogService, reviewQueueService, cache);
        verify(urlRepository, never()).save(any());
        assertThat(links.values()).allSatisfy(url -> assertThat(url.isActive()).isTrue());
    }

    @Test
    void blockingAnAccountThatIsAlreadyInactiveStillBlocksItsLinksWithoutDeactivatingItAgain() {
        final var dormant = user(5L, "dormant@example.com");
        dormant.setActive(false);
        when(userRepository.findById(5L)).thenReturn(Optional.of(dormant));
        final var url = link(40L, "https://a.example/", dormant);

        assertThat(moderationService.blockUser(5L, ADMIN, "admin", null)).isEqualTo(1);

        verify(userService, never()).deactivate(any());
        assertThat(url.getBlockReason()).isEqualTo(BlockReason.USER_BLOCKED);
    }

    @Test
    void blockingAUserRefreshesThePossibleScammersOnceTheBlockIsSaved() {
        final var user = user(5L, "rhea@gmail.com");
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));
        link(40L, "https://a.example/", user);

        moderationService.blockUser(5L, ADMIN, "admin", null);

        final var order = inOrder(userService, urlRepository, scammerSignalService);
        order.verify(userService).deactivate(user);
        order.verify(urlRepository).save(argThat(url -> url.getId() == 40L));
        order.verify(scammerSignalService).invalidate();
        assertThat(outOfPlace).isEmpty();
    }

    @Test
    void blockingTheUserFromAReviewRefreshesThePossibleScammers() {
        final var user = user(5L, "rhea@gmail.com");
        link(40L, "https://a.example/", user);

        moderationService.resolveReview(40L, ReviewAction.BLOCK_USER, null, ADMIN, "admin");

        verify(scammerSignalService).invalidate();
        assertThat(outOfPlace).isEmpty();
    }

    @Test
    void reactivatingAUserRefreshesThePossibleScammersOnceTheAccountAndLinksAreSaved() {
        final var user = user(5L, "rhea@gmail.com");
        user.setActive(false);
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));
        blocked(50L, "https://a.example/", BlockReason.USER_BLOCKED, user);

        moderationService.reactivateUser(5L, ADMIN, "admin");

        final var order = inOrder(userService, urlRepository, scammerSignalService);
        order.verify(userService).persistUser(user);
        order.verify(urlRepository).save(argThat(url -> url.getId() == 50L));
        order.verify(scammerSignalService).invalidate();
        assertThat(outOfPlace).isEmpty();
    }

    @Test
    void aRefusedUserBlockLeavesThePossibleScammersAlone() {
        final var admin = user(5L, ADMIN);
        when(userRepository.findById(5L)).thenReturn(Optional.of(admin));
        when(userRepository.existsByIdAndAuthoritiesName(5L, "ROLE_ADMIN")).thenReturn(true);

        assertThatThrownBy(() -> moderationService.blockUser(5L, ADMIN, "admin", null)).isInstanceOf(UrlValidationException.class);

        verifyNoInteractions(scammerSignalService);
    }

    @Test
    void blockingAnUnknownUserIsNotFound() {
        when(userRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> moderationService.blockUser(9L, ADMIN, "admin", null)).isInstanceOf(UserNotFoundException.class);
        assertThatThrownBy(() -> moderationService.reactivateUser(9L, ADMIN, "admin")).isInstanceOf(UserNotFoundException.class);
        verifyNoInteractions(auditLogService);
    }

    @Test
    void reactivatingAUserRestoresOnlyTheLinksBlockedWithTheAccount() {
        final var user = user(5L, "alice@example.com");
        user.setActive(false);
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));
        final var first = blocked(50L, "https://a.example/", BlockReason.USER_BLOCKED, user);
        final var second = blocked(51L, "https://b.example/", BlockReason.USER_BLOCKED, user);
        final var byRule = blocked(52L, "https://jofogas.cards/", BlockReason.DOMAIN_RULE, user);

        final var restored = moderationService.reactivateUser(5L, ADMIN, "admin");

        assertThat(restored).isEqualTo(2);
        assertThat(user.getActive()).isTrue();
        verify(userService).persistUser(user);
        assertThat(List.of(first, second)).allSatisfy(url -> {
            assertThat(url.isActive()).isTrue();
            assertThat(url.getBlockReason()).isNull();
            assertThat(url.getBlockDetail()).isNull();
            assertThat(url.getBlockedAt()).isNull();
            verify(cache).evict(url.getShortUrl());
        });
        assertThat(byRule.isActive()).isFalse();
        assertThat(byRule.getBlockReason()).isEqualTo(BlockReason.DOMAIN_RULE);
        verify(auditLogService).log(ADMIN, AuditAction.REACTIVATE_USER, "User", "5", "links: 2, via: admin");
        assertThat(outOfPlace).isEmpty();
    }

    @Test
    void anAdminAccountIsNotBlockedEvenUnderTheCap() {
        final var admin = user(5L, ADMIN);
        when(userRepository.findById(5L)).thenReturn(Optional.of(admin));
        when(userRepository.existsByIdAndAuthoritiesName(5L, "ROLE_ADMIN")).thenReturn(true);
        final var url = link(40L, "https://a.example/", admin);

        assertThatThrownBy(() -> moderationService.blockUser(5L, ADMIN, "mcp", 0))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("Admin accounts can't be blocked here.");
        assertThatThrownBy(() -> moderationService.resolveReview(40L, ReviewAction.BLOCK_USER, null, ADMIN, "admin"))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("Admin accounts can't be blocked here.");

        assertThat(url.isActive()).isTrue();
        verifyNoInteractions(userService, sendingEmailService, auditLogService, reviewQueueService);
        verify(urlRepository, never()).save(any());
    }

    @Test
    void reactivatingAUserLeavesALinkBlockedWhenAnotherActiveLinkHoldsItsAlias() {
        final var user = user(5L, "alice@example.com");
        when(userRepository.findById(5L)).thenReturn(Optional.of(user));
        final var clashing = blocked(50L, "https://a.example/", BlockReason.USER_BLOCKED, user);
        clashing.setShortUrl("promo");
        link(51L, "https://legacy.example/", null).setShortUrl("promo");
        final var free = blocked(52L, "https://b.example/", BlockReason.USER_BLOCKED, user);

        assertThat(moderationService.reactivateUser(5L, ADMIN, "admin")).isEqualTo(1);

        assertThat(clashing.isActive()).isFalse();
        assertThat(clashing.getBlockReason()).isEqualTo(BlockReason.USER_BLOCKED);
        verify(cache, never()).evict("promo");
        assertThat(free.isActive()).isTrue();
    }

    @Test
    void trustingADomainLeavesALinkBlockedWhenAnotherActiveLinkHoldsItsAlias() {
        final var clashing = blocked(20L, "https://jofogas.cards/a", BlockReason.BLOCKLIST, null);
        clashing.setShortUrl("promo");
        link(21L, "https://example.org/", null).setShortUrl("promo");
        when(linkCheckService.check(anyString())).thenReturn(ALLOWED);

        moderationService.allowDomain("jofogas.cards", null, ADMIN, "admin");

        assertThat(clashing.isActive()).isFalse();
        assertThat(clashing.getBlockReason()).isEqualTo(BlockReason.BLOCKLIST);
        verify(auditLogService).log(ADMIN, AuditAction.ALLOW_DOMAIN, "Domain", "jofogas.cards", "reviews: 0, links: 0, via: admin");
    }

    @Test
    void lookingFineResolvesTheReviewAsOk() {
        final var url = link(60L, "https://www.jofogas.cards/login", null);

        moderationService.resolveReview(60L, ReviewAction.OK, null, ADMIN, "admin");

        verify(reviewQueueService).resolveOpen(url, ReviewResolution.OK, ADMIN);
        assertThat(url.isActive()).isTrue();
        assertThat(rules).isEmpty();
        verify(auditLogService).log(ADMIN, AuditAction.RESOLVE_REVIEW, "Url", "60", "action: OK, links: 0, via: admin");
    }

    @Test
    void trustingFromAReviewTrustsTheExactHostByDefault() {
        final var url = link(61L, "https://docs.google.com/forms/d/x", null);

        moderationService.resolveReview(61L, ReviewAction.TRUST_DOMAIN, null, ADMIN, "admin");

        assertThat(rules).containsOnlyKeys("docs.google.com");
        assertThat(rules.get("docs.google.com").getType()).isEqualTo(DomainRuleType.ALLOW);
        verify(reviewQueueService).resolveOpen(url, ReviewResolution.DOMAIN_TRUSTED, ADMIN);
        verify(auditLogService).log(ADMIN, AuditAction.RESOLVE_REVIEW, "Url", "61", "action: TRUST_DOMAIN, domain: docs.google.com, reviews: 0, links: 0, via: admin");
    }

    @Test
    void trustingFromAReviewUsesThePassedDomain() {
        link(61L, "https://docs.google.com/forms/d/x", null);

        moderationService.resolveReview(61L, ReviewAction.TRUST_DOMAIN, "Google.com", ADMIN, "admin");

        assertThat(rules).containsOnlyKeys("google.com");
    }

    @Test
    void blockingADomainFromAReviewBlocksTheRegistrableDomainByDefault() {
        final var url = link(62L, "https://www.jofogas.cards/login", null);
        final var sibling = link(63L, "https://m.jofogas.cards/", null);

        moderationService.resolveReview(62L, ReviewAction.BLOCK_DOMAIN, null, ADMIN, "admin");

        assertThat(rules).containsOnlyKeys("jofogas.cards");
        assertThat(rules.get("jofogas.cards").getType()).isEqualTo(DomainRuleType.BLOCK);
        assertThat(url.getBlockReason()).isEqualTo(BlockReason.DOMAIN_RULE);
        assertThat(sibling.getBlockReason()).isEqualTo(BlockReason.DOMAIN_RULE);
        verify(reviewQueueService, atLeastOnce()).resolveOpen(url, ReviewResolution.DOMAIN_BLOCKED, ADMIN);
        verify(auditLogService).log(ADMIN, AuditAction.RESOLVE_REVIEW, "Url", "62", "action: BLOCK_DOMAIN, domain: jofogas.cards, links: 2, via: admin");
    }

    @Test
    void blockingADomainFromAReviewUsesThePassedDomain() {
        final var url = link(62L, "https://www.jofogas.cards/login", null);
        final var sibling = link(63L, "https://m.jofogas.cards/", null);

        moderationService.resolveReview(62L, ReviewAction.BLOCK_DOMAIN, "www.jofogas.cards", ADMIN, "admin");

        assertThat(rules).containsOnlyKeys("www.jofogas.cards");
        assertThat(url.getBlockReason()).isEqualTo(BlockReason.DOMAIN_RULE);
        assertThat(sibling.isActive()).isTrue();
    }

    @Test
    void aPassedDomainMustCoverTheReviewedLink() {
        link(62L, "https://www.jofogas.cards/login", null);

        assertThatThrownBy(() -> moderationService.resolveReview(62L, ReviewAction.BLOCK_DOMAIN, "example.org", ADMIN, "admin"))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("example.org doesn't cover this link's destination www.jofogas.cards.");
        assertThat(rules).isEmpty();
        verifyNoInteractions(auditLogService, reviewQueueService);
    }

    @Test
    void blockingTheLinkFromAReviewBlocksItAsAnAdminBlock() {
        final var url = link(64L, "https://example.org/x", null);

        moderationService.resolveReview(64L, ReviewAction.BLOCK_LINK, null, ADMIN, "admin");

        assertThat(url.getBlockReason()).isEqualTo(BlockReason.ADMIN);
        verify(reviewQueueService, atLeastOnce()).resolveOpen(url, ReviewResolution.LINK_BLOCKED, ADMIN);
        verify(auditLogService).log(ADMIN, AuditAction.RESOLVE_REVIEW, "Url", "64", "action: BLOCK_LINK, links: 1, via: admin");
    }

    @Test
    void blockingTheUserOfAnAnonymousLinkIsRefused() {
        final var url = link(65L, "https://example.org/x", null);

        assertThatThrownBy(() -> moderationService.resolveReview(65L, ReviewAction.BLOCK_USER, null, ADMIN, "admin"))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("This link has no owner to block.");
        assertThat(url.isActive()).isTrue();
        verifyNoInteractions(userService, auditLogService, reviewQueueService);
    }

    @Test
    void blockingTheUserFromAReviewBlocksTheOwnerAndTheirLinks() {
        final var owner = user(5L, "scammer@example.com");
        final var url = link(66L, "https://example.org/x", owner);
        final var other = link(67L, "https://example.net/y", owner);

        moderationService.resolveReview(66L, ReviewAction.BLOCK_USER, null, ADMIN, "admin");

        verify(userService).deactivate(owner);
        assertThat(url.getBlockReason()).isEqualTo(BlockReason.USER_BLOCKED);
        assertThat(other.getBlockReason()).isEqualTo(BlockReason.USER_BLOCKED);
        verifyNoInteractions(sendingEmailService);
        verify(reviewQueueService, atLeastOnce()).resolveOpen(url, ReviewResolution.USER_BLOCKED, ADMIN);
        verify(auditLogService).log(ADMIN, AuditAction.RESOLVE_REVIEW, "Url", "66", "action: BLOCK_USER, user: 5, links: 2, via: admin");
    }

    @ParameterizedTest
    @EnumSource(ReviewAction.class)
    void resolvingAReviewWritesExactlyOneAuditEntry(final ReviewAction action) {
        link(70L, "https://www.jofogas.cards/login", user(5L, "scammer@example.com"));
        lenient().when(linkCheckService.check(anyString())).thenReturn(ALLOWED);

        moderationService.resolveReview(70L, action, null, ADMIN, "mcp");

        verify(auditLogService).log(eq(ADMIN), eq(AuditAction.RESOLVE_REVIEW), eq("Url"), eq("70"),
                argThat(details -> details.startsWith("action: " + action.name()) && details.endsWith("via: mcp")));
        verifyNoMoreInteractions(auditLogService);
    }

    @Test
    void addingABlockRuleBlocksTheDomainAndReturnsTheSavedRuleWithTheLinksBlocked() {
        link(10L, "https://jofogas.cards/login", user(1L, "alice@example.com"));
        link(11L, "https://www.jofogas.cards/item/7", null);
        link(12L, "https://example.org/", null);

        final var created = moderationService.createDomainRule(new DomainRuleDto(" JOFOGAS.cards ", DomainRuleType.BLOCK, "phishing wave"), ADMIN, "admin");

        assertThat(created.linksAffected()).isEqualTo(2);
        assertThat(created.rule())
                .extracting(DomainRuleResponse::domain, DomainRuleResponse::type, DomainRuleResponse::reason, DomainRuleResponse::createdBy)
                .containsExactly("jofogas.cards", DomainRuleType.BLOCK, "phishing wave", ADMIN);
        verify(auditLogService).log(ADMIN, AuditAction.BLOCK_DOMAIN, "Domain", "jofogas.cards", "reason: phishing wave, links: 2, via: admin");
        verifyNoMoreInteractions(auditLogService);
    }

    @Test
    void addingABlockRuleBlocksEveryMatchingLinkWithoutACap() {
        LongStream.rangeClosed(1, 60).forEach(id -> link(id, "https://jofogas.cards/" + id, null));

        final var created = moderationService.createDomainRule(new DomainRuleDto("jofogas.cards", DomainRuleType.BLOCK, null), ADMIN, "admin");

        assertThat(created.linksAffected()).isEqualTo(60);
    }

    @Test
    void addingAnAllowRuleTrustsTheDomainAndReturnsTheRestoredLinks() {
        final var existing = rule("jofogas.cards", DomainRuleType.BLOCK);
        blocked(20L, "https://jofogas.cards/a", BlockReason.BLOCKLIST, null);
        blocked(21L, "https://jofogas.cards/b", BlockReason.ADMIN, null);
        when(linkCheckService.check("https://jofogas.cards/a")).thenReturn(ALLOWED);

        final var created = moderationService.createDomainRule(new DomainRuleDto("jofogas.cards", DomainRuleType.ALLOW, null), ADMIN, "admin");

        assertThat(created.linksAffected()).isEqualTo(1);
        assertThat(created.rule())
                .extracting(DomainRuleResponse::id, DomainRuleResponse::domain, DomainRuleResponse::type, DomainRuleResponse::reason,
                        DomainRuleResponse::createdBy, DomainRuleResponse::createdAt)
                .containsExactly(existing.getId(), "jofogas.cards", DomainRuleType.ALLOW, "seeded", "system", RULE_CREATED_AT);
    }

    @Test
    void aRuleWithoutATypeIsRefusedBeforeAnythingChanges() {
        link(10L, "https://jofogas.cards/login", null);

        assertThatThrownBy(() -> moderationService.createDomainRule(new DomainRuleDto("jofogas.cards", null, "phishing wave"), ADMIN, "admin"))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage("Choose whether the rule blocks or allows the domain");

        assertThat(rules).isEmpty();
        assertThat(links.get(10L).isActive()).isTrue();
        verifyNoInteractions(auditLogService, eventPublisher);
    }

    @Test
    void aRuleForSomethingThatIsNotADomainIsRefused() {
        assertThatThrownBy(() -> moderationService.createDomainRule(new DomainRuleDto("com", DomainRuleType.BLOCK, null), ADMIN, "admin"))
                .isInstanceOf(UrlValidationException.class)
                .hasMessage(NOT_A_DOMAIN);

        assertThat(rules).isEmpty();
    }

    @Test
    void removingADomainRuleByIdRemovesThatRule() {
        rule("njuskaio.cam", DomainRuleType.BLOCK);
        final var existing = rule("jofogas.cards", DomainRuleType.ALLOW);
        when(domainRuleRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        moderationService.removeDomainRule(existing.getId(), ADMIN, "admin");

        verify(domainRuleRepository).delete(existing);
        verify(eventPublisher).publishEvent(any(BlocklistConfigurationChangedEvent.class));
        verify(auditLogService).log(ADMIN, AuditAction.REMOVE_DOMAIN_RULE, "Domain", "jofogas.cards", "type: ALLOW, links: 0, via: admin");
    }

    @Test
    void removingAnUnknownRuleByIdIsNotFound() {
        when(domainRuleRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> moderationService.removeDomainRule(99L, ADMIN, "admin"))
                .isInstanceOf(DomainRuleNotFoundException.class)
                .hasMessage("There is no rule with id 99.");
        verify(domainRuleRepository, never()).delete(any());
        verifyNoInteractions(auditLogService, eventPublisher);
    }

    @Test
    void domainRulesOfOneTypeComeNewestFirstWithEveryField() {
        final var older = rule("jofogas.cards", DomainRuleType.BLOCK);
        final var newer = rule("njuskaio.cam", DomainRuleType.BLOCK);
        when(domainRuleRepository.findByTypeOrderByCreatedAtDescIdDesc(DomainRuleType.BLOCK)).thenReturn(List.of(newer, older));

        assertThat(moderationService.domainRules(DomainRuleType.BLOCK)).containsExactly(
                new DomainRuleResponse(newer.getId(), "njuskaio.cam", DomainRuleType.BLOCK, "seeded", "system", RULE_CREATED_AT),
                new DomainRuleResponse(older.getId(), "jofogas.cards", DomainRuleType.BLOCK, "seeded", "system", RULE_CREATED_AT));
    }

    @Test
    void domainRulesWithoutATypeListEveryRuleNewestFirst() {
        final var block = rule("jofogas.cards", DomainRuleType.BLOCK);
        final var allow = rule("gableci.hrva.cc", DomainRuleType.ALLOW);
        when(domainRuleRepository.findAll(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))).thenReturn(List.of(allow, block));

        assertThat(moderationService.domainRules(null)).extracting(DomainRuleResponse::domain).containsExactly("gableci.hrva.cc", "jofogas.cards");
    }

    @Test
    void removingADomainRuleDeletesItAndLogsIt() {
        final var existing = rule("jofogas.cards", DomainRuleType.BLOCK);

        moderationService.removeDomainRule("JOFOGAS.cards", ADMIN, "admin");

        verify(domainRuleRepository).delete(existing);
        verify(auditLogService).log(ADMIN, AuditAction.REMOVE_DOMAIN_RULE, "Domain", "jofogas.cards", "type: BLOCK, links: 0, via: admin");
    }

    @Test
    void everyDomainRuleChangeTellsTheClickCheckToReloadAfterTheRuleIsSaved() {
        moderationService.blockDomain("jofogas.cards", null, ADMIN, "admin", null);
        moderationService.allowDomain("njuskaio.cam", null, ADMIN, "admin");
        moderationService.removeDomainRule("jofogas.cards", ADMIN, "admin");

        final var order = inOrder(domainRuleRepository, eventPublisher);
        order.verify(domainRuleRepository).save(argThat(rule -> rule.getDomain().equals("jofogas.cards")));
        order.verify(eventPublisher).publishEvent(any(BlocklistConfigurationChangedEvent.class));
        order.verify(domainRuleRepository).save(argThat(rule -> rule.getDomain().equals("njuskaio.cam")));
        order.verify(eventPublisher).publishEvent(any(BlocklistConfigurationChangedEvent.class));
        order.verify(domainRuleRepository).delete(argThat(rule -> rule.getDomain().equals("jofogas.cards")));
        order.verify(eventPublisher).publishEvent(any(BlocklistConfigurationChangedEvent.class));
    }

    @Test
    void blockingOneLinkLeavesTheClickCheckAlone() {
        link(30L, "https://jofogas.cards/login", null);

        moderationService.blockLink(30L, BlockReason.ADMIN, null, ADMIN, "admin");

        verify(eventPublisher, never()).publishEvent(any(BlocklistConfigurationChangedEvent.class));
    }

    @Test
    void removingARuleThatDoesNotExistTellsTheClickCheckNothing() {
        assertThatThrownBy(() -> moderationService.removeDomainRule("example.org", ADMIN, "admin"))
                .isInstanceOf(DomainRuleNotFoundException.class);

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void removingARuleThatDoesNotExistIsNotFound() {
        assertThatThrownBy(() -> moderationService.removeDomainRule("example.org", ADMIN, "admin"))
                .isInstanceOf(DomainRuleNotFoundException.class)
                .hasMessage("There is no rule for example.org.");
        verify(domainRuleRepository, never()).delete(any());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void deletingALinkRemovesItsVisitsAndTheLinkThenEvictsIt() {
        final var url = link(80L, "https://example.org/x", user(1L, "alice@example.com"));

        moderationService.deleteLink(80L, ADMIN, "mcp");

        final var order = inOrder(ipAddressService, urlRepository, cache);
        order.verify(ipAddressService).deleteRecordsForUrl(url);
        order.verify(urlRepository).delete(url);
        order.verify(cache).evict("s80");
        verify(auditLogService).log(ADMIN, AuditAction.DELETE_LINK, "Url", "80", "shortUrl: s80, links: 1, via: mcp");
        assertThat(outOfPlace).isEmpty();
    }

    @Test
    void deletingAnUnknownLinkIsNotFound() {
        assertThatThrownBy(() -> moderationService.deleteLink(404L, ADMIN, "admin")).isInstanceOf(UrlNotFoundException.class);
        verify(urlRepository, never()).delete(any(Url.class));
        verifyNoInteractions(auditLogService);
    }

    @Test
    void resolvingSeveralReviewsResolvesEachOneAndListsTheFailuresWithoutStopping() {
        final var scammer = user(5L, "scammer@example.com");
        final var admin = user(6L, "other-admin@hrva.cc");
        when(userRepository.existsByIdAndAuthoritiesName(5L, "ROLE_ADMIN")).thenReturn(false);
        when(userRepository.existsByIdAndAuthoritiesName(6L, "ROLE_ADMIN")).thenReturn(true);
        final var owned = link(70L, "https://example.org/a", scammer);
        link(71L, "https://example.org/b", null);
        final var adminLink = link(72L, "https://example.org/c", admin);
        link(73L, "https://example.org/d", scammer);
        openReviews(70L, 71L, 72L, 404L);

        final var result = moderationService.resolveReviews(List.of(70L, 71L, 72L, 404L, 73L), ReviewAction.BLOCK_USER, ADMIN, "admin");

        assertThat(result.resolved()).containsExactly(70L);
        assertThat(result.failed()).containsExactly(
                new ReviewBulkResolveResponse.Failure(71L, "This link has no owner to block."),
                new ReviewBulkResolveResponse.Failure(72L, "Admin accounts can't be blocked here."),
                new ReviewBulkResolveResponse.Failure(404L, "Url doesn't exist"),
                new ReviewBulkResolveResponse.Failure(73L, "This link is no longer in the review queue."));
        assertThat(owned.getBlockReason()).isEqualTo(BlockReason.USER_BLOCKED);
        assertThat(adminLink.isActive()).isTrue();
        verify(userService).deactivate(scammer);
        verify(userService, never()).deactivate(admin);
        verify(auditLogService).log(ADMIN, AuditAction.RESOLVE_REVIEW, "Url", "70", "action: BLOCK_USER, user: 5, links: 2, via: admin");
        verifyNoMoreInteractions(auditLogService);
        assertThat(outOfPlace).isEmpty();
    }

    @Test
    void eachResolvedLinkGetsTheSameAuditEntryAsASingleResolve() {
        link(70L, "https://example.org/a", null);
        link(71L, "https://example.net/b", null);
        openReviews(70L, 71L);

        moderationService.resolveReviews(List.of(70L, 71L), ReviewAction.BLOCK_LINK, ADMIN, "admin");

        verify(auditLogService).log(ADMIN, AuditAction.RESOLVE_REVIEW, "Url", "70", "action: BLOCK_LINK, links: 1, via: admin");
        verify(auditLogService).log(ADMIN, AuditAction.RESOLVE_REVIEW, "Url", "71", "action: BLOCK_LINK, links: 1, via: admin");
        verifyNoMoreInteractions(auditLogService);
        assertThat(savedPerTransaction).filteredOn(saved -> !saved.isEmpty()).containsExactly(List.of(70L), List.of(71L));
    }

    @Test
    void aDomainSharedBySeveralSelectedLinksIsBlockedOnceAndTheirReviewsAreResolvedUnderIt() {
        final var alice = user(1L, "alice@example.com");
        final var first = link(62L, "https://www.jofogas.cards/login", alice);
        final var second = link(63L, "https://m.jofogas.cards/", alice);
        final var third = link(64L, "https://jofogas.cards/x", null);
        final var inactive = link(65L, "https://jofogas.cards/old", null);
        inactive.setActive(false);
        final var other = link(66L, "https://example.org/", null);
        openReviews(62L, 63L, 64L, 65L, 66L);

        final var result = moderationService.resolveReviews(List.of(62L, 63L, 64L, 65L, 66L), ReviewAction.BLOCK_DOMAIN, ADMIN, "admin");

        assertThat(result.resolved()).containsExactly(62L, 63L, 64L, 65L, 66L);
        assertThat(result.failed()).isEmpty();
        assertThat(rules).containsOnlyKeys("jofogas.cards", "example.org");
        verify(domainRuleRepository, times(2)).save(any(DomainRule.class));
        verify(eventPublisher, times(2)).publishEvent(any(BlocklistConfigurationChangedEvent.class));
        assertThat(List.of(first, second, third)).allSatisfy(url -> assertThat(url.getBlockDetail()).isEqualTo("jofogas.cards"));
        assertThat(other.getBlockDetail()).isEqualTo("example.org");
        verify(sendingEmailService).sendEmailUrlBlocked(alice, first);
        verify(sendingEmailService).sendEmailUrlBlocked(alice, second);
        verifyNoMoreInteractions(sendingEmailService);
        verify(reviewQueueService).resolveOpen(inactive, ReviewResolution.DOMAIN_BLOCKED, ADMIN);
        verify(auditLogService).log(ADMIN, AuditAction.RESOLVE_REVIEW, "Url", "62", "action: BLOCK_DOMAIN, domain: jofogas.cards, links: 3, via: admin");
        verify(auditLogService).log(ADMIN, AuditAction.RESOLVE_REVIEW, "Url", "63", "action: BLOCK_DOMAIN, domain: jofogas.cards, links: 0, via: admin");
        verify(auditLogService).log(ADMIN, AuditAction.RESOLVE_REVIEW, "Url", "64", "action: BLOCK_DOMAIN, domain: jofogas.cards, links: 0, via: admin");
        verify(auditLogService).log(ADMIN, AuditAction.RESOLVE_REVIEW, "Url", "65", "action: BLOCK_DOMAIN, domain: jofogas.cards, links: 0, via: admin");
        verify(auditLogService).log(ADMIN, AuditAction.RESOLVE_REVIEW, "Url", "66", "action: BLOCK_DOMAIN, domain: example.org, links: 1, via: admin");
        verifyNoMoreInteractions(auditLogService);
        assertThat(outOfPlace).isEmpty();
    }

    @Test
    void aHostSharedBySeveralSelectedLinksIsTrustedOnceAndAnotherHostOnItsOwn() {
        link(61L, "https://docs.google.com/forms/d/x", null);
        link(62L, "https://docs.google.com/forms/d/y", null);
        link(63L, "https://drive.google.com/file/z", null);
        openReviews(61L, 62L, 63L);

        final var result = moderationService.resolveReviews(List.of(61L, 62L, 63L), ReviewAction.TRUST_DOMAIN, ADMIN, "admin");

        assertThat(result.resolved()).containsExactly(61L, 62L, 63L);
        assertThat(rules).containsOnlyKeys("docs.google.com", "drive.google.com");
        assertThat(rules.values()).allSatisfy(rule -> assertThat(rule.getType()).isEqualTo(DomainRuleType.ALLOW));
        verify(domainRuleRepository, times(2)).save(any(DomainRule.class));
        verify(auditLogService).log(ADMIN, AuditAction.RESOLVE_REVIEW, "Url", "62", "action: TRUST_DOMAIN, domain: docs.google.com, links: 0, via: admin");
    }

    @Test
    void aLinkWithoutADomainFailsAloneInABulkDomainBlock() {
        link(62L, "https://jofogas.cards/login", null);
        link(63L, "http://203.0.113.7/login", null);
        openReviews(62L, 63L);

        final var result = moderationService.resolveReviews(List.of(63L, 62L), ReviewAction.BLOCK_DOMAIN, ADMIN, "admin");

        assertThat(result.resolved()).containsExactly(62L);
        assertThat(result.failed()).containsExactly(new ReviewBulkResolveResponse.Failure(63L, NOT_A_DOMAIN));
        assertThat(rules).containsOnlyKeys("jofogas.cards");
    }

    @Test
    void anUnexpectedFailureIsReportedWithoutItsInternalsAndTheRestGoOn() {
        final var broken = link(70L, "https://example.org/a", null);
        final var fine = link(71L, "https://example.org/b", null);
        openReviews(70L, 71L);
        doThrow(new DataAccessResourceFailureException("connection to 10.0.0.5:5432 refused"))
                .when(reviewQueueService).resolveOpen(broken, ReviewResolution.OK, ADMIN);

        final var result = moderationService.resolveReviews(List.of(70L, 71L), ReviewAction.OK, ADMIN, "admin");

        assertThat(result.resolved()).containsExactly(71L);
        assertThat(result.failed()).containsExactly(new ReviewBulkResolveResponse.Failure(70L, "Resolving this link failed. Try again."));
        verify(reviewQueueService).resolveOpen(fine, ReviewResolution.OK, ADMIN);
    }

    @Test
    void anAdminResolvingSeveralReviewsSendsNoAlert() {
        link(30L, "https://example.org/x", null);
        link(31L, "https://jofogas.cards/y", null);
        openReviews(30L, 31L);

        moderationService.resolveReviews(List.of(30L), ReviewAction.BLOCK_LINK, ADMIN, "admin");
        moderationService.resolveReviews(List.of(31L), ReviewAction.BLOCK_DOMAIN, ADMIN, "admin");

        assertThat(links.values()).allSatisfy(url -> assertThat(url.isActive()).isFalse());
        verify(eventPublisher, never()).publishEvent(any(AdminAlertEvent.class));
    }

    private TransactionOperations recordingTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(final TransactionCallback<T> action) {
                savedPerTransaction.add(new ArrayList<>());
                inTransaction = true;
                try {
                    return action.doInTransaction(new SimpleTransactionStatus());
                } finally {
                    inTransaction = false;
                }
            }
        };
    }

    private void openReviews(final Long... urlIds) {
        when(reviewQueueService.openUrlIds(anyCollection())).thenReturn(Set.of(urlIds));
    }

    private List<LinkDestination> page(final long afterId, final Pageable pageable, final Predicate<Url> filter) {
        return links.values().stream()
                .filter(url -> url.getId() > afterId)
                .filter(filter)
                .limit(pageable.getPageSize())
                .map(url -> new LinkDestination(url.getId(), url.getLongUrl()))
                .toList();
    }

    private List<Long> ids(final Predicate<Url> filter) {
        return links.values().stream().filter(filter).map(Url::getId).toList();
    }

    private static boolean ownedBy(final Url url, final User user) {
        return url.getOwner() != null && Objects.equals(url.getOwner().getId(), user.getId());
    }

    private Url link(final long id, final String longUrl, final User owner) {
        final var url = Url.builder().id(id).shortUrl("s" + id).longUrl(longUrl).owner(owner).active(true).build();
        links.put(id, url);
        return url;
    }

    private Url blocked(final long id, final String longUrl, final BlockReason reason, final User owner) {
        final var url = link(id, longUrl, owner);
        url.setActive(false);
        url.setBlockReason(reason);
        url.setBlockDetail(reason == BlockReason.BLOCKLIST ? "URLhaus" : "detail");
        url.setBlockedAt(BLOCKED_AT);
        return url;
    }

    private DomainRule rule(final String domain, final DomainRuleType type) {
        final var rule = DomainRule.builder().id((long) rules.size() + 1).domain(domain).type(type).reason("seeded").createdBy("system")
                .createdAt(RULE_CREATED_AT).build();
        rules.put(domain, rule);
        return rule;
    }

    private static LinkReview review(final Url url) {
        return LinkReview.builder().id(url.getId()).url(url).reasons("login or verification lure").flaggedAt(LocalDateTime.now()).build();
    }

    private static User user(final long id, final String email) {
        return User.builder().id(id).email(email).active(true).build();
    }

}
