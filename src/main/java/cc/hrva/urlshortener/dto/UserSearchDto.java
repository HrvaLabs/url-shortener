package cc.hrva.urlshortener.dto;

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

}
