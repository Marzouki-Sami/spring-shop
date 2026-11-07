package com.marzouki.shop.geo;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.marzouki.shop.security.AppUserDetails;
import com.marzouki.shop.support.TestPasswords;
import com.marzouki.shop.user.Role;
import com.marzouki.shop.user.User;
import com.marzouki.shop.user.UserRepository;
import com.marzouki.shop.user.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** With the map switched off the address form still works by typing, and the lookup endpoints say so. */
@SpringBootTest(properties = "app.geo.enabled=false")
@AutoConfigureMockMvc
class GeoDisabledTest {

    @Autowired MockMvc mvc;
    @Autowired UserService users;
    @Autowired UserRepository userRepository;

    private User created;

    @AfterEach
    void cleanUp() {
        if (created != null) {
            userRepository.deleteById(created.getId());
        }
    }

    @Test
    void noMapAndNoLookup() throws Exception {
        User cy = created = users.create("Cy", "Customer", "cy@example.com", TestPasswords.strong(), Role.CUSTOMER);
        var asCy = user(new AppUserDetails(cy));
        mvc.perform(get("/account/addresses/new").with(asCy)).andExpect(status().isOk())
                .andExpect(content().string(containsString("data-geo=\"false\"")))
                .andExpect(content().string(containsString("name=\"address\"")))
                .andExpect(content().string(not(containsString("/webjars/leaflet/"))));
        mvc.perform(get("/geo/search").param("q", "main street").with(asCy)).andExpect(status().isServiceUnavailable());
    }
}