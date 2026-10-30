package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.enums.AuditAction;
import java.time.LocalDate;

public record AuditLogFilter(AuditAction action, String search, LocalDate from, LocalDate to) {

    public static final AuditLogFilter NONE = new AuditLogFilter(null, null, null, null);

}
