package faye.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FayeApplication {

    public static void main(String[] args) {
        SpringApplication.run(FayeApplication.class, args);
    }
}
