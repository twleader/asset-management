package com.steven.assets.externalmaterials;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ExternalMaterialsApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExternalMaterialsApplication.class, args);
    }
}
