package com.liveproof.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域配置：仅放行本地前端开发端口（阶段 2 Vite 默认 5173）。
 * 比赛现场前后端同机，端口白名单足够，不使用通配符。
 *
 * <p>方法白名单必须覆盖前端真实使用的方法。之前漏了 {@code DELETE}，
 * 浏览器侧删除素材 / 移除字幕全部被 Spring 判为
 * {@code 403 Invalid CORS request}（请求到不了 Controller，业务规则根本没机会执行）；
 * 同机 curl 不带 {@code Origin} 却能返回 204，很容易被误判成「按钮坏了」。
 * 这里只补系统实际存在的 DELETE，不放开 PUT 等未使用的方法。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://127.0.0.1:5173", "http://localhost:5173",
                        "http://127.0.0.1:8080", "http://localhost:8080")
                .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
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
