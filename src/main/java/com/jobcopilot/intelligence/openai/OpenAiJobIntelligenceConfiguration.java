package com.jobcopilot.intelligence.openai;

import com.jobcopilot.intelligence.JobIntelligenceModel;
import com.jobcopilot.intelligence.JobIntelligencePrompt;
import com.jobcopilot.intelligence.JobIntelligenceRunner;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.LogLevel;
import com.openai.core.Timeout;
import java.net.URI;
import java.io.IOException;
import java.time.Duration;
import okhttp3.RequestBody;
import okhttp3.MediaType;
import okio.BufferedSink;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenAiJobIntelligenceProperties.class)
@ConditionalOnProperty(prefix = "job-intelligence.openai", name = "enabled", havingValue = "true")
public class OpenAiJobIntelligenceConfiguration {
    @Bean(destroyMethod = "close")
    OpenAIClient openAiClient(OpenAiJobIntelligenceProperties properties) {
        URI baseUrl = properties.validatedBaseUrl();
        return new OpenAIClientImpl(ClientOptions.builder()
                .httpClient(new com.openai.client.okhttp.OkHttpClient(transport(properties.connectTimeout())))
                .apiKey(properties.apiKey())
                .baseUrl(baseUrl.toASCIIString())
                .timeout(Timeout.builder().connect(properties.connectTimeout()).build())
                .maxRetries(0)
                .logLevel(LogLevel.OFF)
                .build());
    }

    static okhttp3.OkHttpClient transport(Duration connectTimeout) {
        return new okhttp3.OkHttpClient.Builder()
                .connectTimeout(connectTimeout)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .addInterceptor(chain -> {
                    var request = chain.request();
                    var body = request.body();
                    if (body == null) return chain.proceed(request);
                    // OkHttp can follow 503 + Retry-After: 0 even with connection retries off.
                    // Declare POST bodies non-replayable so all follow-up paths return the first response.
                    var once = new RequestBody() {
                        @Override public MediaType contentType() { return body.contentType(); }
                        @Override public long contentLength() throws IOException { return body.contentLength(); }
                        @Override public boolean isOneShot() { return true; }
                        @Override public void writeTo(BufferedSink sink) throws IOException { body.writeTo(sink); }
                    };
                    return chain.proceed(request.newBuilder().method(request.method(), once).build());
                }).build();
    }

    @Bean
    @ConditionalOnMissingBean(JobIntelligenceModel.class)
    JobIntelligenceModel openAiJobIntelligenceModel(OpenAIClient client) {
        return new OpenAiJobIntelligenceModel(client);
    }

    @Bean
    JobIntelligencePrompt jobIntelligencePrompt() {
        return new JobIntelligencePrompt();
    }

    @Bean
    JobIntelligenceRunner jobIntelligenceRunner(JobIntelligencePrompt prompt, JobIntelligenceModel model) {
        return new JobIntelligenceRunner(prompt, model);
    }
}
