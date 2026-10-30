package cc.hrva.urlshortener.dto.settings;

public record PublicSettingsResponse(boolean anonymousLinks, boolean newSignups, boolean customAliases, boolean googleSignIn, boolean githubSignIn) {

}
