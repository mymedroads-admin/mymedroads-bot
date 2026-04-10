package com.mymedroads.bot.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Duration;

@Configuration
public class OllamaHttpConfig {

    @Bean
    RestClientCustomizer ollamaRestClientCustomizer() {
        return builder -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofSeconds(15));
            factory.setReadTimeout(Duration.ofSeconds(120));
            builder.requestFactory(factory);
        };
    }
}
