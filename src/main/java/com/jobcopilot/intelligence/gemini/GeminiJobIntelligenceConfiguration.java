package com.jobcopilot.intelligence.gemini;

import com.jobcopilot.intelligence.JobIntelligenceModel;
import com.jobcopilot.intelligence.JobIntelligencePrompt;
import com.jobcopilot.intelligence.JobIntelligenceRunner;
import java.net.http.HttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GeminiJobIntelligenceProperties.class)
@ConditionalOnProperty(prefix = "job-intelligence.gemini", name = "enabled", havingValue = "true")
public class GeminiJobIntelligenceConfiguration {
    @Bean
    HttpClient geminiHttpClient(GeminiJobIntelligenceProperties properties) {
        properties.validatedBaseUrl();
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(JobIntelligenceModel.class)
    JobIntelligenceModel geminiJobIntelligenceModel(HttpClient client, GeminiJobIntelligenceProperties properties) {
        return new GeminiJobIntelligenceModel(client, properties.validatedBaseUrl(), properties.apiKey());
    }

    @Bean
    JobIntelligencePrompt jobIntelligencePrompt() { return new JobIntelligencePrompt(); }

    @Bean
    JobIntelligenceRunner jobIntelligenceRunner(JobIntelligencePrompt prompt, JobIntelligenceModel model) {
        return new JobIntelligenceRunner(prompt, model);
    }
}
