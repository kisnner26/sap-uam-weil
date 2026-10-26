package ni.edu.uam.campusfood.assessment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Tests publicados con su versión vigente. Un test nuevo agrega aquí su definición. */
@Configuration
class PublishedTests {

    @Bean
    TestDefinition weilFormA() {
        return new TestDefinition(WeilScoringStrategy.TEST_ID, 1, 60, 8);
    }
}
