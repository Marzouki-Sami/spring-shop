package com.marzouki.shop.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AccessControlTest {

    @Autowired MockMvc mvc;

    @Test
    void guestsCanBrowseTheShop() throws Exception {
        for (String path : new String[] {"/", "/products", "/login", "/register"}) {
            mvc.perform(get(path)).andExpect(status().isOk());
        }
    }

    @Test
    void guestsAreSentToSignInForProtectedPages() throws Exception {
        for (String path : new String[] {"/account", "/manage/products", "/admin/users"}) {
            mvc.perform(get(path)).andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrlPattern("**/login"));
        }
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void customersCanUseTheirAccountButNotTheBackOffice() throws Exception {
        mvc.perform(get("/manage/products")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/users")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void managersCanManageTheCatalogButNotUsers() throws Exception {
        mvc.perform(get("/manage/products")).andExpect(status().isOk());
        mvc.perform(get("/admin/users")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminsCanDoEverythingBelowThem() throws Exception {
        mvc.perform(get("/manage/products")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void changesWithoutACsrfTokenAreRejected() throws Exception {
        mvc.perform(post("/manage/categories").param("title", "Books")).andExpect(status().isForbidden());
        mvc.perform(post("/manage/categories").param("title", "Books").with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void responsesCarryHardeningHeaders() throws Exception {
        mvc.perform(get("/"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    @WithMockUser
    void unknownPagesGiveANotFoundStatus() throws Exception {
        mvc.perform(get("/no-such-page")).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void customersCannotChangeUsersOrExport() throws Exception {
        for (String action : new String[] {"role", "enabled", "reset-2fa", "unlock"}) {
            mvc.perform(post("/admin/users/1/" + action).with(csrf())).andExpect(status().isForbidden());
        }
        mvc.perform(get("/manage/orders/export")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "MANAGER")
    void managersCannotChangeUsers() throws Exception {
        for (String action : new String[] {"role", "enabled", "reset-2fa", "unlock"}) {
            mvc.perform(post("/admin/users/1/" + action).with(csrf())).andExpect(status().isForbidden());
        }
        mvc.perform(get("/admin/audit")).andExpect(status().isForbidden());
        mvc.perform(get("/admin/settings")).andExpect(status().isForbidden());
    }}
