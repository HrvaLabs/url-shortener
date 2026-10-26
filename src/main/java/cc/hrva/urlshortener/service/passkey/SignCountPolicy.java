package cc.hrva.urlshortener.service.passkey;

public final class SignCountPolicy {

    private SignCountPolicy() {}

    public static boolean isRegression(final long stored, final long presented) {
        return presented > 0 && presented <= stored;
    }

}
