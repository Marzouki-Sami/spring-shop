package com.marzouki.shop.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marzouki.shop.support.TestPasswords;
import com.marzouki.shop.user.Role;
import com.marzouki.shop.user.UserRepository;
import com.marzouki.shop.user.UserService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** A customer who is promoted must not turn an old refresh token into a staff session without two-factor. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "app.mfa.staff2fa=true")
class StaffRefreshTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserService users;
    @Autowired UserRepository userRepository;

    @Test
    void aPromotedCustomersOldRefreshTokenStopsWorking() throws Exception {
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("firstName", "Pat", "lastName", "Roe",
                                "email", "pat@example.com", "password", TestPasswords.strong()))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String refresh = json.readTree(body).get("refreshToken").asText();
        Long id = userRepository.findByEmailIgnoreCase("pat@example.com").orElseThrow().getId();

        users.changeRole(-1L, id, Role.MANAGER);

        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isUnauthorized());
    }
}
