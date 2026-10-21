package cc.hrva.urlshortener.client;

import java.nio.file.Path;

public record FetchResult(boolean notModified, Path body, String etag, String lastModified) {}
