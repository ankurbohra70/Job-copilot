package com.jobcopilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.boot.context.properties.ConfigurationPropertiesScan
public class JobCopilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobCopilotApplication.class, args);
    }
}
