package com.yu.agent4;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Agent4Application {

    public static void main(String[] args) {
        System.out.println("The skill name (no arguments). E.g., \"pdf\" or \"xlsx\"");
        SpringApplication.run(Agent4Application.class, args);
    }

}
