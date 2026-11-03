package com.afterbooth.inventory.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.afterbooth.inventory.dto.OrganizationResponse;
import com.afterbooth.inventory.service.OrganizationService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

@org.springframework.boot.test.context.SpringBootTest(
    properties = "spring.docker.compose.enabled=false")
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc(
    print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@org.springframework.context.annotation.Import({
  com.afterbooth.AuthenticatedMockMvcConfiguration.class,
  com.afterbooth.TestcontainersConfiguration.class
})
class OrganizationControllerTest {
  @Autowired MockMvc mvc;
  @MockitoBean OrganizationService organizations;

  @Test
  void listsOrganizationsWithOnlyContractFields() throws Exception {
    when(organizations.list())
        .thenReturn(
            List.of(
                new OrganizationResponse(1L, "Giver"), new OrganizationResponse(2L, "Receiver")));

    mvc.perform(get("/api/organizations"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/json"))
        .andExpect(
            content()
                .json(
                    "[{\"id\":1,\"name\":\"Giver\"},{\"id\":2,\"name\":\"Receiver\"}]",
                    JsonCompareMode.STRICT));
  }

  @Test
  void returnsEmptyArrayWhenThereAreNoOrganizations() throws Exception {
    when(organizations.list()).thenReturn(List.of());

    mvc.perform(get("/api/organizations"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]", JsonCompareMode.STRICT));
  }
}
