package cc.hrva.urlshortener.dto.bio;

public record BioHandleAvailabilityResponse(String handle, boolean available, String reason) {
}
