package bank.pbp.orchestrator.temporal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP clients for the services the sagas call. Correlation headers are added by platform-common.
 * Uses the JDK HttpClient: the older HttpURLConnection-based factory rejects PATCH, which the
 * mapping service uses for status and KYC changes.
 */
@Configuration
public class InternalClients {

    @Bean
    RestClient mappingClient(RestClient.Builder b, @Value("${pbp.services.mapping-url}") String url) {
        return build(b, url);
    }

    @Bean
    RestClient atfinityClient(RestClient.Builder b, @Value("${pbp.services.atfinity-adapter-url}") String url) {
        return build(b, url);
    }

    @Bean
    RestClient essenceClient(RestClient.Builder b, @Value("${pbp.adapters.essence-url}") String url) {
        return build(b, url);
    }

    @Bean
    RestClient threddClient(RestClient.Builder b, @Value("${pbp.services.thredd-adapter-url}") String url) {
        return build(b, url);
    }

    @Bean
    RestClient creditClient(RestClient.Builder b, @Value("${pbp.services.credit-adapter-url}") String url) {
        return build(b, url);
    }

    private static RestClient build(RestClient.Builder b, String url) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(1))
                .build();
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory(http);
        f.setReadTimeout(Duration.ofSeconds(12));
        return b.clone().baseUrl(url).requestFactory(f).build();
    }
}
