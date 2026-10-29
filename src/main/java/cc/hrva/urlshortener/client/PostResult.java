package cc.hrva.urlshortener.client;

public record PostResult(int httpStatus, String failure) {

    public boolean delivered() {
        return httpStatus / 100 == 2;
    }

}
