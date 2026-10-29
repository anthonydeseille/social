package fr.deseille.social;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Horloge injectable : les dates du droit du travail s'apprécient à l'heure de Paris. */
@Configuration(proxyBeanMethods = false)
public class HorlogeConfiguration {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Europe/Paris"));
    }
}
