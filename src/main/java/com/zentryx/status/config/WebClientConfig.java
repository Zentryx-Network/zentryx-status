package com.zentryx.status.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Single shared WebClient with strict timeouts so a slow target
 * never holds up the next round of polling. Connect = 4s,
 * read/write = configured in StatusProperties (default 8s).
 */
@Configuration
@EnableConfigurationProperties(StatusProperties.class)
public class WebClientConfig {

    @Bean
    WebClient pollerWebClient(StatusProperties props) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 4_000)
                .responseTimeout(Duration.ofMillis(props.timeoutMs()))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(props.timeoutMs(), TimeUnit.MILLISECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(props.timeoutMs(), TimeUnit.MILLISECONDS)))
                // Don't follow redirects — a 301 from a target counts as "not 200" and we want
                // to know about that, not silently chase it.
                .followRedirect(false);

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("User-Agent", "ZentryxStatus/0.1.0 (+https://zentryxnet.lat/status)")
                .build();
    }
}
