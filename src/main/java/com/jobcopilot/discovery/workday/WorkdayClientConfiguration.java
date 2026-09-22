package com.jobcopilot.discovery.workday;

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
@EnableConfigurationProperties(WorkdayClientProperties.class)
public class WorkdayClientConfiguration {
    @Bean OkHttpClient workdayHttpClient(WorkdayClientProperties properties) {
        return transport(properties.connectTimeout(), properties.requestTimeout());
    }

    static OkHttpClient transport(Duration connectTimeout, Duration requestTimeout) {
        return new OkHttpClient.Builder()
                .connectTimeout(connectTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .readTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .callTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
                .protocols(List.of(Protocol.HTTP_1_1))
                .addNetworkInterceptor(WorkdayClientConfiguration::suppressStatusRetry).build();
    }

    private static Response suppressStatusRetry(Interceptor.Chain chain) throws IOException {
        Response response = chain.proceed(chain.request());
        return response.code() == 503 ? response.newBuilder().removeHeader("Retry-After").build() : response;
    }

    @Bean WorkdayPostingGateway workdayPostingGateway(OkHttpClient workdayHttpClient,
            WorkdayClientProperties properties) {
        return new HttpWorkdayPostingGateway(workdayHttpClient, new WorkdayEndpointResolver(),
                new WorkdayResponseDecoder(), properties.maxSearchResponseBytes(),
                properties.maxDetailResponseBytes());
    }
}
