package com.abing.fileintegration.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * 配置 CORS 跨域
     */

       @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")          // 允许的来源
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")                 // 允许的请求头
                .allowCredentials(true)              // 是否允许携带 Cookie
                .maxAge(3600);                       // 预检请求缓存时间（秒）
    }
}