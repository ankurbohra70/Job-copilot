package com.jobcopilot.discovery.lever;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LeverClientProperties.class)
public class LeverClientConfiguration {
    @Bean
    OkHttpClient leverHttpClient(LeverClientProperties properties) {
        return transport(properties.connectTimeout(), properties.requestTimeout());
    }

    static OkHttpClient transport(Duration connectTimeout, Duration requestTimeout) {
        return new OkHttpClient.Builder()
                .connectTimeout(connectTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .callTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                // HTTP/2 connection coalescing has its own 421 follow-up path. It is unnecessary for this connector.
                .protocols(List.of(Protocol.HTTP_1_1))
                // Remove every provider value before OkHttp can parse it or perform its special 503 follow-up.
                .addNetworkInterceptor(LeverClientConfiguration::suppressStatusRetry)
                .build();
    }

    private static Response suppressStatusRetry(Interceptor.Chain chain) throws IOException {
        Response response = chain.proceed(chain.request());
        return response.code() == 503
                ? response.newBuilder().removeHeader("Retry-After").build()
                : response;
    }

    @Bean
    LeverPostingGateway leverPostingGateway(OkHttpClient leverHttpClient, LeverClientProperties properties) {
        return new HttpLeverPostingGateway(leverHttpClient, new LeverEndpointResolver(),
                new LeverResponseDecoder(), properties.maxResponseBytes());
    }
}
