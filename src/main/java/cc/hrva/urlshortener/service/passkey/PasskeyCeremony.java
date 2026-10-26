package cc.hrva.urlshortener.service.passkey;

public record PasskeyCeremony(PasskeyCeremonyPurpose purpose, String challenge, Long userId) {
}
