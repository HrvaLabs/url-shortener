package cc.hrva.urlshortener.service.alert;

import cc.hrva.urlshortener.model.enums.BlockReason;
import cc.hrva.urlshortener.model.enums.ReportCategory;
import java.util.List;

public sealed interface AdminAlertEvent {

    record ReviewOpened(AlertedLink link, List<String> reasons) implements AdminAlertEvent {}

    record VisitorReported(AlertedLink link, ReportCategory category, String details, boolean reviewOpened) implements AdminAlertEvent {}

    record LinkBlocked(AlertedLink link, BlockReason reason, String detail) implements AdminAlertEvent {}

    record UserSignedUp(Long userId, String authProvider) implements AdminAlertEvent {}

    record BlocklistSourceFailed(String sourceName, String error, boolean fallbackLoaded) implements AdminAlertEvent {}

    record BlocklistSourceRecovered(String sourceName) implements AdminAlertEvent {}

}
