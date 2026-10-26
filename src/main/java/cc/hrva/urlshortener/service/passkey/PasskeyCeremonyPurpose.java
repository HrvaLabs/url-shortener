package cc.hrva.urlshortener.service.passkey;

import org.springframework.security.web.webauthn.api.UserVerificationRequirement;

public enum PasskeyCeremonyPurpose {

    REGISTRATION,
    SIGN_IN,
    SECOND_STEP,
    TWO_STEP_DISABLE;

    public UserVerificationRequirement userVerification() {
        return this == SIGN_IN ? UserVerificationRequirement.REQUIRED : UserVerificationRequirement.PREFERRED;
    }

}
