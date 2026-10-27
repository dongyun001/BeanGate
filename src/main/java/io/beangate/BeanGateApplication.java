package io.beangate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BeanGateApplication {

    public static void main(String[] args) {
        SpringApplication.run(BeanGateApplication.class, args);
    }
}
