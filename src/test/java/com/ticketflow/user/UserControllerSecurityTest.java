package com.ticketflow.user;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.config.SecurityConfig;
import com.ticketflow.config.SecurityErrorHandlers;
import com.ticketflow.user.dto.UserResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest({UserController.class, AdminUserController.class})
@Import({SecurityConfig.class, SecurityErrorHandlers.class})
class UserControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor asUser(long id, String role) {
        return jwt().jwt(j -> j.subject(String.valueOf(id))).authorities(new SimpleGrantedAuthority(role));
    }

    @Test
    void meWithoutTokenIs401ProblemDetail() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.instance").value("/api/users/me"));
        verifyNoInteractions(userService);
    }

    @Test
    void meWithTokenReturnsCurrentUser() throws Exception {
        when(userService.getMe(5L)).thenReturn(new UserResponse(5L, "ana@example.com", "Ana", Role.CUSTOMER));

        mockMvc.perform(get("/api/users/me").with(asUser(5, "ROLE_CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.email").value("ana@example.com"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void adminEndpointAsCustomerIs403ProblemDetail() throws Exception {
        mockMvc.perform(patch("/api/admin/users/9/role").with(asUser(5, "ROLE_CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ORGANIZER\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
        verifyNoInteractions(userService);
    }

    @Test
    void adminEndpointWithoutTokenIs401() throws Exception {
        mockMvc.perform(patch("/api/admin/users/9/role")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ORGANIZER\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanGrantOrganizer() throws Exception {
        when(userService.changeRole(9L, Role.ORGANIZER))
                .thenReturn(new UserResponse(9L, "bob@example.com", "Bob", Role.ORGANIZER));

        mockMvc.perform(patch("/api/admin/users/9/role").with(asUser(1, "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ORGANIZER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ORGANIZER"));
    }

    @Test
    void adminRoleRequestWithoutRoleIs400() throws Exception {
        mockMvc.perform(patch("/api/admin/users/9/role").with(asUser(1, "ROLE_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("role"));
    }
}
