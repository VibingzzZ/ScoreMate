package com.abing.assessment.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 本地单机应用，页面与接口同源，本不需要 CORS。这里保留最窄的配置，只为开发期独立前端调试。
 *
 * <p><b>绝不能写 allowedOriginPatterns("*")</b>：那等于任何网站都能跨域调本机接口，
 * 包括拿着用户填好的 Key 去调模型的那个。比监听 0.0.0.0 更隐蔽。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("http://127.0.0.1:*", "http://localhost:*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
