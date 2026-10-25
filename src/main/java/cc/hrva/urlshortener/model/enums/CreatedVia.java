package cc.hrva.urlshortener.model.enums;

public enum CreatedVia {

    WEB,
    API_KEY,
    BULK,
    MCP,
    ANONYMOUS_WEB,
    ANONYMOUS_MCP;

    public CreatedVia anonymous() {
        return switch (this) {
            case WEB -> ANONYMOUS_WEB;
            case MCP -> ANONYMOUS_MCP;
            default -> this;
        };
    }

}
