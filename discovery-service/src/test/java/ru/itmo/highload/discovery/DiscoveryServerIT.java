package ru.itmo.highload.discovery;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS,
        properties = {"spring.cloud.config.enabled=false",
                "spring.config.import=classpath:discovery-config/application.yml,classpath:discovery-config/discovery-service.yml"})
class DiscoveryServerIT {
    @Autowired TestRestTemplate http;
    @Autowired com.netflix.eureka.cluster.PeerEurekaNodes peers;

    @Test void standaloneRegistryDoesNotCreateReplicationPeers() {
        assertThat(peers.getPeerEurekaNodes()).isEmpty();
    }

    @Test void exposesRegistry() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        var result = http.exchange("/eureka/apps", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).contains("applications");
    }
}
