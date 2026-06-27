package com.snmpweb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class SnmpNetconfToolApplication {

    public static void main(String[] args) {
        SpringApplication.run(SnmpNetconfToolApplication.class, args);
    }
}
