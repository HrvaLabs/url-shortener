package cc.hrva.urlshortener.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record SignInMethodsResponse(
        @Schema(description = "Every way the account has signed in, in the order GET /users/me lists them") List<SignInMethodResponse> signInMethods) {
}
