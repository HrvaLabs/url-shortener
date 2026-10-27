package cc.hrva.urlshortener.service;

import cc.hrva.urlshortener.dto.AdminUserResponse;
import cc.hrva.urlshortener.dto.DataExportDto;
import cc.hrva.urlshortener.dto.DeleteAccountDto;
import cc.hrva.urlshortener.dto.PasswordResetDto;
import cc.hrva.urlshortener.dto.RequestPasswordResetDto;
import cc.hrva.urlshortener.dto.UpdatePasswordDto;
import cc.hrva.urlshortener.dto.UserDto;
import cc.hrva.urlshortener.dto.UserSearchDto;
import cc.hrva.urlshortener.dto.UserUpdateDto;
import cc.hrva.urlshortener.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UserService {

    User getUserFromToken();
    User register(User user);
    UserDto fetchCurrentUser();
    Page<AdminUserResponse> fetchAllUsers(Pageable pageable, UserSearchDto search);
    void persistUser(User user);
    void deleteUserById(Long id);
    void deleteOwnAccount(DeleteAccountDto deleteAccountDto);
    DataExportDto exportMyData();
    void userHasLoggedIn(User user);
    void deactivateUnusedUserAccounts();
    void deactivate(User user);
    boolean reactivate(long userId);
    User fetchUserFromEmail(String email);
    User fetchActiveUserByEmail(String email);
    AdminUserResponse updateUser(UserUpdateDto userUpdateDto);
    User updatePassword(UpdatePasswordDto updatePasswordDto);
    User resetPassword(PasswordResetDto passwordResetDto);
    void sendPasswordResetLinkToUser(RequestPasswordResetDto requestPasswordResetDto);

}
