package com.example.httpreading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class HttpReadingApplication {
    public static void main(String[] args) {
        SpringApplication.run(HttpReadingApplication.class, args);
    }
}
