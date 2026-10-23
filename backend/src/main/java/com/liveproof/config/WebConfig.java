package com.liveproof.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置：仅放行本地前端开发端口（阶段 2 Vite 默认 5173）。
 * 比赛现场前后端同机，端口白名单足够，不使用通配符。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://127.0.0.1:5173", "http://localhost:5173",
                        "http://127.0.0.1:8080", "http://localhost:8080")
                .allowedMethods("GET", "POST", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
        registry.addMapping("/media/**")
                .allowedOrigins("http://127.0.0.1:5173", "http://localhost:5173",
                        "http://127.0.0.1:8080", "http://localhost:8080")
                .allowedMethods("GET", "HEAD", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Range", "Accept-Ranges", "Content-Length")
                .maxAge(3600);
    }
}
