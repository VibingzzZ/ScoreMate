package com.abing.assessment.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 本地单机应用：页面由本服务自己提供，属于同源请求，本来并不需要 CORS。
 * 这里保留一份"最窄"的配置，只为兼容开发阶段用独立前端调试的情况。
 *
 * <p><b>绝不能写 allowedOriginPatterns("*")</b>：那等于让浏览器里任何一个网站
 * 都能跨域调用 http://127.0.0.1:端口 的接口——包括那个用用户填好的 API Key
 * 去调用模型的接口。这是比"端口监听 0.0.0.0"更隐蔽的一个洞。
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
