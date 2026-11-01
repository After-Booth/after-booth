package com.afterbooth.global.config;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

// Serves the React build baked into the image (Dockerfile). Unknown client routes get index.html;
// /api/**, /actuator/** and missing files keep their 404. Replaces Boot's default /** mapping
// (spring.web.resources.add-mappings=false)
@Configuration
class SpaWebConfig implements WebMvcConfigurer {

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry
        .addResourceHandler("/**")
        .addResourceLocations("classpath:/static/")
        .resourceChain(true)
        .addResolver(new SpaFallbackResolver());
  }

  static class SpaFallbackResolver extends PathResourceResolver {

    @Override
    protected Resource getResource(String resourcePath, Resource location) throws IOException {
      Resource resource = super.getResource(resourcePath, location);
      if (resource != null || !isClientRoute(resourcePath)) {
        return resource;
      }
      return super.getResource("index.html", location);
    }

    static boolean isClientRoute(String path) {
      String firstSegment = path.split("/", 2)[0];
      String lastSegment = path.substring(path.lastIndexOf('/') + 1);
      return !firstSegment.equals("api")
          && !firstSegment.equals("actuator")
          && !lastSegment.contains(".");
    }
  }
}
