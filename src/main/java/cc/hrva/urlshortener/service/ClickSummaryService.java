package cc.hrva.urlshortener.service;

public interface ClickSummaryService {

    void sendDaily();

    void sendDailyInBackground();

    void sendWeekly();

    void sendWeeklyInBackground();

}
