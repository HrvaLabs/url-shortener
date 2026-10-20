package cc.hrva.urlshortener.mcp;

import java.io.IOException;

public class RequestBodyTooLargeException extends IOException {

    public RequestBodyTooLargeException() {
        super("Request body exceeds " + McpRequestBodyLimitFilter.MAX_BODY_BYTES + " bytes");
    }

}
