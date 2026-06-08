package com.example.httpreading.config;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import com.example.httpreading.memory.storage.QdrantStore;

import reactor.netty.http.client.HttpClient;

@Configuration
@EnableConfigurationProperties(QdrantProperties.class)
public class QdrantConfig {
	@Bean
	public WebClient qdrantWebClient(QdrantProperties properties) {
		String baseUrl = properties.getUrl() == null || properties.getUrl().isBlank()
				? "http://localhost:6333"
				: properties.getUrl();

		WebClient.Builder builder = WebClient.builder().baseUrl(baseUrl)
				.defaultHeader("Content-Type", "application/json");

		if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
			builder.defaultHeader("api-key", properties.getApiKey());
		}

		int timeoutSeconds = properties.getTimeout() <= 0 ? 30 : properties.getTimeout();
		HttpClient httpClient = HttpClient.create()
				.responseTimeout(Duration.ofSeconds(timeoutSeconds))
				.option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutSeconds * 1000);

		builder.clientConnector(new ReactorClientHttpConnector(httpClient));
		return builder.build();
	}

	@Bean
	public QdrantStore qdrantStore(QdrantProperties properties, WebClient qdrantWebClient) {
		return new QdrantStore(properties, qdrantWebClient);
	}
}
