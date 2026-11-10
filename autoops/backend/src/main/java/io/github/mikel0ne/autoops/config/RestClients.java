package io.github.mikel0ne.autoops.config;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

public final class RestClients {

    private RestClients() {
    }

    public static RestClient.Builder builder(HttpClientProperties props) {
        return RestClient.builder().requestFactory(requestFactory(props));
    }

    static ClientHttpRequestFactory requestFactory(HttpClientProperties props) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(props.readTimeout());
        return factory;
    }
}
