package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.exception.InvalidSortException;
import cc.hrva.urlshortener.model.EmailLog;
import cc.hrva.urlshortener.model.enums.EmailStatus;
import cc.hrva.urlshortener.repository.EmailLogRepository;
import cc.hrva.urlshortener.service.impl.DefaultEmailLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultEmailLogServiceTest {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    @Mock
    private EmailLogRepository emailLogRepository;

    private EmailLogService emailLogService;

    @BeforeEach
    void setUp() {
        emailLogService = new DefaultEmailLogService(emailLogRepository);
    }

    @Test
    void withoutAStatusTheWholeLogComesNewestFirst() {
        final Page<EmailLog> everything = Page.empty();
        when(emailLogRepository.findAll(PageRequest.of(0, 50, NEWEST_FIRST))).thenReturn(everything);

        assertThat(emailLogService.emails(null, PageRequest.of(0, 50))).isSameAs(everything);
    }

    @Test
    void aStatusKeepsOnlyTheEmailsWithIt() {
        final Page<EmailLog> failed = Page.empty();
        when(emailLogRepository.findAllByStatus("FAILED", PageRequest.of(2, 10, NEWEST_FIRST))).thenReturn(failed);

        assertThat(emailLogService.emails(EmailStatus.FAILED, PageRequest.of(2, 10))).isSameAs(failed);
    }

    @Test
    void theLogCanBeReadOldestFirst() {
        final var oldestFirst = PageRequest.of(0, 50, Sort.by(Sort.Order.asc("createdAt"), Sort.Order.desc("id")));
        when(emailLogRepository.findAll(oldestFirst)).thenReturn(Page.empty());

        emailLogService.emails(null, PageRequest.of(0, 50, Sort.by(Sort.Order.asc("createdAt"))));

        verify(emailLogRepository).findAll(oldestFirst);
    }

    @Test
    void theLogRefusesToSortByAFieldOffTheList() {
        assertThatThrownBy(() -> emailLogService.emails(null, PageRequest.of(0, 50, Sort.by("body"))))
                .isInstanceOf(InvalidSortException.class)
                .hasMessage("Sort must be createdAt");
        verifyNoInteractions(emailLogRepository);
    }

}
