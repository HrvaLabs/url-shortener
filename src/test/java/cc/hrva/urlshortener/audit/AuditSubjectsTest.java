package cc.hrva.urlshortener.audit;

import cc.hrva.urlshortener.repository.CustomDomainRepository;
import cc.hrva.urlshortener.repository.CustomDomainRequestRepository;
import cc.hrva.urlshortener.repository.DomainAuditSubject;
import cc.hrva.urlshortener.repository.DomainRequestAuditSubject;
import cc.hrva.urlshortener.repository.LinkAuditSubject;
import cc.hrva.urlshortener.repository.UrlRepository;
import cc.hrva.urlshortener.repository.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditSubjectsTest {

    @Mock
    private CustomDomainRepository customDomainRepository;

    @Mock
    private CustomDomainRequestRepository customDomainRequestRepository;

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private AuditSubjects auditSubjects;

    @BeforeEach
    void setUp() {
        auditSubjects = new AuditSubjects(customDomainRepository, customDomainRequestRepository, urlRepository, userRepository, transactionManager);
    }

    @Test
    void aCustomDomainIsNamedByItsHostnameAndOwner() {
        when(customDomainRepository.findAuditSubjectById(3L)).thenReturn(Optional.of(new DomainAuditSubject("go.example.com", "owner@example.com")));

        assertThat(auditSubjects.describe("CustomDomain", "3")).contains("domain: go.example.com, owner: owner@example.com");
    }

    @Test
    void aCustomDomainWithoutAnOwnerLeavesTheOwnerOut() {
        when(customDomainRepository.findAuditSubjectById(3L)).thenReturn(Optional.of(new DomainAuditSubject("go.example.com", null)));

        assertThat(auditSubjects.describe("CustomDomain", "3")).contains("domain: go.example.com");
    }

    @Test
    void aLimitRequestIsNamedByItsAccountAndBothLimits() {
        when(customDomainRequestRepository.findAuditSubjectById(1L)).thenReturn(Optional.of(new DomainRequestAuditSubject("owner@example.com", 2, 5)));

        assertThat(auditSubjects.describe("DomainRequest", "1")).contains("account: owner@example.com, limit: 2 → 5");
    }

    @Test
    void aLinkOnACustomDomainIsNamedByHostnameAliasAndDestination() {
        when(urlRepository.findAuditSubjectById(265L)).thenReturn(Optional.of(new LinkAuditSubject("promo", "https://example.com/page?a=1", "go.example.com")));

        assertThat(auditSubjects.describe("Url", "265")).contains("link: go.example.com/promo, destination: https://example.com/page?a=1");
    }

    @Test
    void aLinkOnHrvaCcHasNoHostnameOfItsOwn() {
        when(urlRepository.findAuditSubjectById(265L)).thenReturn(Optional.of(new LinkAuditSubject("promo", "https://example.com/page", null)));

        assertThat(auditSubjects.describe("Url", "265")).contains("link: hrva.cc/promo, destination: https://example.com/page");
    }

    @Test
    void aUserIsNamedByEmail() {
        when(userRepository.findEmailById(26L)).thenReturn(Optional.of("owner@example.com"));

        assertThat(auditSubjects.describe("User", "26")).contains("account: owner@example.com");
    }

    @Test
    void aLongDestinationIsKeptWhole() {
        final var destination = "https://example.com/" + "a".repeat(2000);
        when(urlRepository.findAuditSubjectById(7L)).thenReturn(Optional.of(new LinkAuditSubject("long", destination, null)));

        assertThat(auditSubjects.describe("Url", "7")).contains("link: hrva.cc/long, destination: " + destination);
    }

    @Test
    void unknownTypeIsEmpty() {
        assertThat(auditSubjects.describe("EmailLog", "7")).isEmpty();

        verifyNoInteractions(customDomainRepository, customDomainRequestRepository, urlRepository, userRepository, transactionManager);
    }

    @Test
    void missingRowIsEmpty() {
        when(urlRepository.findAuditSubjectById(9L)).thenReturn(Optional.empty());

        assertThat(auditSubjects.describe("Url", "9")).isEmpty();
    }

    @Test
    void aNonNumericIdentifierIsEmpty() {
        assertThat(auditSubjects.describe("Url", "")).isEmpty();
        assertThat(auditSubjects.describe("User", "abc")).isEmpty();
    }

    @Test
    void aFailingReadIsEmpty() {
        when(userRepository.findEmailById(26L)).thenThrow(new IllegalStateException("database is down"));

        assertThat(auditSubjects.describe("User", "26")).isEmpty();
    }

    @Test
    void theReadRunsInAReadOnlyTransaction() {
        when(userRepository.findEmailById(26L)).thenReturn(Optional.of("owner@example.com"));

        auditSubjects.describe("User", "26");

        final var definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().isReadOnly()).isTrue();
    }

}
