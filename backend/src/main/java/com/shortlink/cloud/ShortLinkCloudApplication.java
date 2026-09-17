package com.shortlink.cloud;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * shortlink-cloud 启动类。
 *
 * @author shortlink-cloud
 */
@EnableAsync
@EnableScheduling
@MapperScan("com.shortlink.cloud.mapper")
@SpringBootApplication
public class ShortLinkCloudApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShortLinkCloudApplication.class, args);
    }
}
