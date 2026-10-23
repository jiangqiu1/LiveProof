package com.liveproof;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * LiveProof 后端入口。
 * 启动：mvn spring-boot:run（端口 8080，与旧 Node 参考实现 8787 分离）
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.liveproof.mapper")
public class LiveProofApplication {

    public static void main(String[] args) {
        SpringApplication.run(LiveProofApplication.class, args);
    }
}
