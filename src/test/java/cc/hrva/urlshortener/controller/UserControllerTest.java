package cc.hrva.urlshortener.controller;

import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import cc.hrva.urlshortener.configuration.GlobalExceptionHandler;
import cc.hrva.urlshortener.dto.SignInMethodResponse;
import cc.hrva.urlshortener.dto.AdminUserResponse;
import cc.hrva.urlshortener.dto.DeleteAccountDto;
import cc.hrva.urlshortener.dto.UpdatePasswordDto;
import cc.hrva.urlshortener.dto.UserDto;
import cc.hrva.urlshortener.dto.UserSearchDto;
import cc.hrva.urlshortener.dto.UserUpdateDto;
import cc.hrva.urlshortener.exception.NoAuthorizationException;
import cc.hrva.urlshortener.exception.ReauthenticationRequiredException;
import cc.hrva.urlshortener.model.User;
import cc.hrva.urlshortener.model.enums.SignInMethod;
import cc.hrva.urlshortener.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    private MockMvc mockMvc;

    @Mock
    private UserService userService;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders.standaloneSetup(new UserController(userService))
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Test
    void shouldGetCurrentUser() throws Exception {
        final var userDto = UserDto.builder().id(1L).email("test@example.com").build();
        when(userService.fetchCurrentUser()).thenReturn(userDto);

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("test@example.com"));
    }

    @Test
    void theCurrentUserSaysWhetherAPasswordIsSet() throws Exception {
        when(userService.fetchCurrentUser()).thenReturn(UserDto.builder().id(1L).email("test@example.com").passwordSet(false)
                .signInMethods(List.of()).build());

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordSet").value(false));
    }

    @Test
    void aUserWithoutTheFieldLeavesItOut() throws Exception {
        when(userService.fetchCurrentUser()).thenReturn(UserDto.builder().id(1L).email("test@example.com").build());

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordSet").doesNotExist());
    }

    @Test
    void shouldGetAllUsers() throws Exception {
        final var userList = Collections.singletonList(AdminUserResponse.from(User.builder().id(1L).email("test@example.com").build(), List.of(), false));
        final var pageable = PageRequest.of(0, 20);
        final var users = new PageImpl<>(userList, pageable, userList.size());
        when(userService.fetchAllUsers(any(Pageable.class), any())).thenReturn(users);

        mockMvc.perform(get("/api/v1/users").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.size()").value(1));
    }

    @Test
    void theUserListFiltersPossibleScammersServerSideAndMarksEachRow() throws Exception {
        final var scammer = AdminUserResponse.from(User.builder().id(27L).email("rhea@gmail.com").build(), List.of(), true);
        final var search = ArgumentCaptor.forClass(UserSearchDto.class);
        when(userService.fetchAllUsers(any(Pageable.class), search.capture())).thenReturn(new PageImpl<>(List.of(scammer), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/users").param("possibleScammer", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(27))
                .andExpect(jsonPath("$.content[0].possibleScammer").value(true))
                .andExpect(jsonPath("$.totalElements").value(1));

        assertThat(search.getValue().getPossibleScammer()).isTrue();
    }

    @Test
    void theUserListFiltersBySignInMethodServerSideAndListsEachRowsMethods() throws Exception {
        final var gitHub = new SignInMethodResponse(SignInMethod.GITHUB, LocalDateTime.of(2026, 10, 20, 8, 0), LocalDateTime.of(2026, 10, 28, 19, 45), null);
        final var row = AdminUserResponse.from(User.builder().id(30L).email("octo@example.com").authProvider("local").build(), List.of(gitHub), false);
        final var search = ArgumentCaptor.forClass(UserSearchDto.class);
        when(userService.fetchAllUsers(any(Pageable.class), search.capture())).thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/users").param("signInMethod", "GITHUB"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].authProvider").value("local"))
                .andExpect(jsonPath("$.content[0].signInMethods[0].method").value("GITHUB"))
                .andExpect(jsonPath("$.content[0].signInMethods[0].lastUsedAt").value("2026-10-28T19:45:00"));

        assertThat(search.getValue().getSignInMethod()).isEqualTo(SignInMethod.GITHUB);
    }

    @Test
    void aUserRowOutsideTheAdminListHasNoPossibleScammerField() throws Exception {
        when(userService.updateUser(any(UserUpdateDto.class))).thenReturn(AdminUserResponse.from(User.builder().id(1L).email("test@example.com").build(), List.of()));

        mockMvc.perform(put("/api/v1/users/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(UserUpdateDto.builder().id(1L).build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.possibleScammer").doesNotHaveJsonPath());
    }

    @Test
    void shouldDeleteUser() throws Exception {
        mockMvc.perform(delete("/api/v1/users/1"))
                .andExpect(status().isNoContent());

        verify(userService).deleteUserById(1L);
    }

    @Test
    void shouldUpdateUser() throws Exception {
        final var user = AdminUserResponse.from(User.builder().id(1L).email("test@example.com").build(), List.of());
        when(userService.updateUser(any(UserUpdateDto.class))).thenReturn(user);

        mockMvc.perform(put("/api/v1/users/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(UserUpdateDto.builder().id(1L).build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void anAdminCannotChangeAnEmailToOneTheAccountCouldNotStore() throws Exception {
        mockMvc.perform(put("/api/v1/users/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(UserUpdateDto.builder().id(1L).email("a".repeat(65) + "@example.com").build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.email").value("Enter a valid email address (e.g. you@example.com)"));

        verifyNoInteractions(userService);
    }

    @Test
    void anAdminCannotChangeAnEmailToAValidOneLongerThanTheAccountCanStore() throws Exception {
        final var email = "a".repeat(64) + "@" + ("b".repeat(63) + ".").repeat(3) + "example.com";

        mockMvc.perform(put("/api/v1/users/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(UserUpdateDto.builder().id(1L).email(email).build())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.email").value("Email must be 255 characters or fewer"));

        verifyNoInteractions(userService);
    }

    @Test
    void shouldExportMyData() throws Exception {
        final var export = new cc.hrva.urlshortener.dto.DataExportDto(
                java.time.LocalDateTime.now(),
                UserDto.builder().id(1L).email("test@example.com").build(),
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList(),
                java.util.Collections.emptyList(),
                3L,
                null,
                new cc.hrva.urlshortener.dto.DataExportDto.NotificationExport(java.util.List.of(), java.util.List.of()),
                java.util.List.of());
        when(userService.exportMyData()).thenReturn(export);

        mockMvc.perform(get("/api/v1/users/me/export"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile.email").value("test@example.com"))
                .andExpect(jsonPath("$.passkeys").isEmpty())
                .andExpect(jsonPath("$.recoveryCodesRemaining").value(3))
                .andExpect(jsonPath("$.bioPage").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.notifications.consentHistory").isEmpty())
                .andExpect(jsonPath("$.notifications.channels").isEmpty())
                .andExpect(jsonPath("$.domains").isEmpty());
    }

    @Test
    void shouldDeleteOwnAccount() throws Exception {
        mockMvc.perform(delete("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"myPassword\"}"))
                .andExpect(status().isNoContent());

        verify(userService).deleteOwnAccount(any());
    }

    @Test
    void shouldDeleteOwnAccountWithoutBody() throws Exception {
        mockMvc.perform(delete("/api/v1/users/me"))
                .andExpect(status().isNoContent());

        verify(userService).deleteOwnAccount(any());
    }

    @Test
    void aStaleGoogleOrGitHubSignInCantDeleteTheAccount() throws Exception {
        doThrow(new ReauthenticationRequiredException("For your security, sign in again to delete your account."))
                .when(userService).deleteOwnAccount(new DeleteAccountDto());

        withErrorHandling().perform(delete("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("""
                        {"error":"For your security, sign in again to delete your account."}"""));
    }

    @Test
    void tooManyWrongPasswordsAre403() throws Exception {
        doThrow(new NoAuthorizationException("Too many wrong passwords. Try again in 24 hours."))
                .when(userService).deleteOwnAccount(DeleteAccountDto.builder().password("wrong").build());

        withErrorHandling().perform(delete("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().json("""
                        {"error":"Too many wrong passwords. Try again in 24 hours."}"""));
    }

    @Test
    void shouldUpdatePassword() throws Exception {
        final var user = User.builder().id(1L).email("test@example.com").build();
        when(userService.updatePassword(any(UpdatePasswordDto.class))).thenReturn(user);

        mockMvc.perform(patch("/api/v1/users/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(UpdatePasswordDto.builder().oldPassword("old").newPassword("newPassword1").build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    private MockMvc withErrorHandling() {
        return MockMvcBuilders.standaloneSetup(new UserController(userService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

}
