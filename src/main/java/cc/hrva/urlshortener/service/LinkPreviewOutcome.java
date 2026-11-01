package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.LinkPreviewResponse;
import java.time.Duration;

public sealed interface LinkPreviewOutcome {

    record Ready(LinkPreviewResponse preview) implements LinkPreviewOutcome {}

    record Limited(Duration retryAfter) implements LinkPreviewOutcome {}

    record Busy() implements LinkPreviewOutcome {}

}
