package com.steven.assets;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AssetManagementApplication {
    public static void main(String[] args) {
        SpringApplication.run(AssetManagementApplication.class, args);
    }
}
