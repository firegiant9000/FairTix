package com.fairtix.organizations.application;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class OrgWebMvcConfig implements WebMvcConfigurer {

  private final OrgScopeInterceptor orgScopeInterceptor;

  public OrgWebMvcConfig(OrgScopeInterceptor orgScopeInterceptor) {
    this.orgScopeInterceptor = orgScopeInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(orgScopeInterceptor)
        .addPathPatterns("/api/**")
        .excludePathPatterns("/api/webhooks/**");
  }
}
