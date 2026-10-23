package ni.edu.uam.campusfood;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SapUamApplication {

    public static void main(String[] args) {
        SpringApplication.run(SapUamApplication.class, args);
    }
}
