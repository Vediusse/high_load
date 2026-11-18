package ru.itmo.highload.common.events;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import ru.itmo.highload.common.testing.KafkaCluster;

class KafkaClusterIT {
    @Test void replicationOrderingAndWritesSurviveOneBrokerLoss() throws Exception {
        try (var cluster = new KafkaCluster().start()) {
            cluster.createTopics();
            var topics=List.of("order-events","production-events","file-events");
            try (var admin = cluster.admin()) {
                assertThat(admin.describeCluster().nodes().get(30,TimeUnit.SECONDS)).extracting(n -> n.id()).containsExactlyInAnyOrder(1,2,3);
                var descriptions=admin.describeTopics(topics).allTopicNames().get(30,TimeUnit.SECONDS);
                for (var description : descriptions.values()) {
                    assertThat(description.partitions()).hasSize(3);
                    description.partitions().forEach(p -> assertThat(p.replicas()).hasSize(3));
                }
                var resources=topics.stream().map(t -> new ConfigResource(ConfigResource.Type.TOPIC,t)).toList();
                admin.describeConfigs(resources).all().get(30,TimeUnit.SECONDS).values()
                        .forEach(config -> assertThat(config.get("min.insync.replicas").value()).isEqualTo("2"));
            }
            Properties props=new Properties();
            props.put("bootstrap.servers",cluster.bootstrapServers());
            props.put("key.serializer",StringSerializer.class); props.put("value.serializer",StringSerializer.class);
            props.put("acks","all"); props.put("enable.idempotence","true");
            props.put("request.timeout.ms","3000"); props.put("delivery.timeout.ms","30000"); props.put("max.block.ms","30000");
            String orderId=UUID.randomUUID().toString();
            try (var producer=new KafkaProducer<String,String>(props)) {
                var first=producer.send(new ProducerRecord<>("order-events",orderId,"confirmed")).get(35,TimeUnit.SECONDS);
                cluster.stopBroker(3);
                var second=producer.send(new ProducerRecord<>("order-events",orderId,"ready")).get(35,TimeUnit.SECONDS);
                assertThat(second.partition()).isEqualTo(first.partition());
                Properties read=new Properties();read.put("bootstrap.servers",cluster.bootstrapServers());
                read.put("key.deserializer",StringDeserializer.class);read.put("value.deserializer",StringDeserializer.class);
                read.put("group.id",UUID.randomUUID().toString());read.put("enable.auto.commit","false");
                try(var consumer=new KafkaConsumer<String,String>(read)) {
                    var partition=new TopicPartition("order-events",first.partition());consumer.assign(List.of(partition));consumer.seekToBeginning(List.of(partition));
                    List<String> received=new ArrayList<>();long deadline=System.nanoTime()+Duration.ofSeconds(40).toNanos();
                    while(received.size()<2 && System.nanoTime()<deadline) consumer.poll(Duration.ofMillis(500)).forEach(r -> {
                        assertThat(r.key()).isEqualTo(orderId);received.add(r.value());
                    });
                    assertThat(received).containsExactly("confirmed","ready");
                }
            }
        }
    }
}
