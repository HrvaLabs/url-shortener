package cc.hrva.urlshortener.service;

public interface ClickSummaryService {

    void sendDaily();

    void sendDailyInBackground();

}
