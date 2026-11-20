package com.allanlewis.homelabwatchman;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class HomelabWatchmanApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(HomelabWatchmanApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
