package com.togather;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TogatherApplication {

    public static void main(String[] args) {
        SpringApplication.run(TogatherApplication.class, args);
    }
}
