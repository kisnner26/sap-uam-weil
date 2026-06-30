package ni.edu.uam.campusfood.config;

import ni.edu.uam.campusfood.moodle.MoodleProperties;
import ni.edu.uam.campusfood.security.JwtProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestClient;

/**
 * Beans transversales: codificador de contrasenas y el RestClient dedicado a Moodle.
 */
@Configuration
@EnableConfigurationProperties({MoodleProperties.class, JwtProperties.class})
public class AppConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * RestClient apuntando a la base de Moodle, con timeouts acotados para que
     * una caida de la plataforma no cuelgue al backend.
     */
    @Bean
    public RestClient moodleRestClient(MoodleProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        factory.setReadTimeout(props.getReadTimeoutMs());

        return RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .build();
    }
}
