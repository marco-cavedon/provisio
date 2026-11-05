package io.github.marcocavedon.provisio.forecasting.internal;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Beans of the forecasting module.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ForecastingProperties.class)
class ForecastingConfiguration {

  /**
   * The forecasting service client, on a RestClient from Spring Boot's builder (its Jackson 3 mapper and observability),
   * with this module's timeouts.
   *
   * <p>HTTP/1.1 explicitly: the JDK client would otherwise offer an HTTP/2 upgrade on plain HTTP, which uvicorn does not
   * speak.
   */
  @Bean
  ForecastingClient forecastingClient(RestClient.Builder builder, ForecastingProperties properties) {
    HttpClient httpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(properties.connectTimeout())
        .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.readTimeout());
    return new ForecastingClient(builder.clone().requestFactory(requestFactory).build(), properties.baseUrl());
  }
}
