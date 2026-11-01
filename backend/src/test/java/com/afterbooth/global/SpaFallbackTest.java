package com.afterbooth.global;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

// global.config.SpaWebConfig with the shipped spring.web.resources.add-mappings=false and
// src/test/resources/static/index.html
@WebMvcTest(SystemController.class)
@TestPropertySource(properties = "afterbooth.ai.mode=replay")
class SpaFallbackTest {

  @Autowired MockMvc mockMvc;

  @Test
  void rootAndClientRoutesServeIndexHtml() throws Exception {
    mockMvc.perform(get("/")).andExpect(status().isOk());
    mockMvc
        .perform(get("/allocation/board"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("spa-shell")));
  }

  @Test
  void unknownApiActuatorAndFilePathsStay404() throws Exception {
    mockMvc
        .perform(get("/api/unknown"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    mockMvc.perform(get("/actuator/unknown")).andExpect(status().isNotFound());
    mockMvc.perform(get("/assets/missing.js")).andExpect(status().isNotFound());
  }
}
