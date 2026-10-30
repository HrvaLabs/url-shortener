package cc.hrva.urlshortener.dto;

import cc.hrva.urlshortener.model.enums.SignInMethod;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserSearchDto {

    private String search;
    private Boolean active;
    private Boolean possibleScammer;
    private SignInMethod signInMethod;

}
