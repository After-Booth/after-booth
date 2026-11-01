package com.afterbooth.global;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

// The Docker image bakes the React build into static/ (Dockerfile); the frontend routes by hash,
// so only / needs the shell. Stands in with src/test/resources/static/index.html
@WebMvcTest(SystemController.class)
@TestPropertySource(properties = "afterbooth.ai.mode=replay")
class SpaShellTest {

  @Autowired MockMvc mockMvc;

  @Test
  void rootForwardsToTheShell() throws Exception {
    mockMvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
    mockMvc
        .perform(get("/index.html"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("spa-shell")));
  }

  @Test
  void unknownApiPathStaysJson404() throws Exception {
    mockMvc
        .perform(get("/api/unknown"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }
}
