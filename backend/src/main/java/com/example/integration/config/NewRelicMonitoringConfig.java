package com.example.integration.config;

import com.newrelic.telemetry.Attributes;
import com.newrelic.telemetry.micrometer.NewRelicRegistry;
import com.newrelic.telemetry.micrometer.NewRelicRegistryConfig;
import io.micrometer.core.instrument.util.NamedThreadFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "newrelic.enabled", havingValue = "true")
public class NewRelicMonitoringConfig {

    @Value("${newrelic.license-key}")
    private String licenseKey;

    @Value("${newrelic.service-name:bouw}")
    private String serviceName;

    @Bean
    public NewRelicRegistry newRelicRegistry() {
        NewRelicRegistryConfig config = new NewRelicRegistryConfig() {
            @Override
            public String get(String key) {
                return null;
            }

            @Override
            public String apiKey() {
                return licenseKey;
            }

            @Override
            public boolean useLicenseKey() {
                return true;
            }

            @Override
            public String serviceName() {
                return serviceName;
            }

            @Override
            public Duration step() {
                return Duration.ofMinutes(1);
            }
        };

        NewRelicRegistry registry = NewRelicRegistry.builder(config)
                .commonAttributes(new Attributes().put("service.name", serviceName))
                .build();
        registry.start(new NamedThreadFactory("newrelic.micrometer.registry"));
        return registry;
    }
}
