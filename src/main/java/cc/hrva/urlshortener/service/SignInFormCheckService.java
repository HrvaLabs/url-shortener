package cc.hrva.urlshortener.service;

public interface SignInFormCheckService {

    boolean check(long urlId);
    void checkLater(long urlId);

}
