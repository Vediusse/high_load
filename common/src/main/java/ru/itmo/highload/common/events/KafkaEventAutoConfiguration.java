package ru.itmo.highload.common.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.common.TopicPartition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.backoff.FixedBackOff;

@AutoConfiguration(beforeName="org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration")
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnProperty(prefix="catering.events",name="enabled",havingValue="true")
@EnableKafka @EnableScheduling
public class KafkaEventAutoConfiguration {
    @Bean EventCodec eventCodec(ObjectMapper mapper) { return new EventCodec(mapper); }
    @Bean ConcurrentKafkaListenerContainerFactory<String,String> kafkaListenerContainerFactory(
            ConsumerFactory<String,String> consumers, KafkaTemplate<String,String> template) {
        var recoverer=new DeadLetterPublishingRecoverer(template,
                (record,error) -> new TopicPartition(record.topic()+".DLT",record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(6));
        var errors=new DefaultErrorHandler(recoverer,new FixedBackOff(500L,2L));
        errors.setAckAfterHandle(true);
        var factory=new ConcurrentKafkaListenerContainerFactory<String,String>();
        factory.setConsumerFactory(consumers);factory.setCommonErrorHandler(errors);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.getContainerProperties().setSyncCommitTimeout(Duration.ofSeconds(5));
        return factory;
    }
    @Bean @ConditionalOnBean(JdbcOutbox.class)
    RelaySchedule relaySchedule(JdbcTemplate jdbc,PlatformTransactionManager transactions,KafkaTemplate<String,String> kafka) {
        var relay=new OutboxRelay(jdbc,transactions,(topic,key,value) -> kafka.send(topic,key,value).get(5,TimeUnit.SECONDS));
        return new RelaySchedule(relay);
    }
    static final class RelaySchedule {
        private final OutboxRelay relay;
        RelaySchedule(OutboxRelay relay) { this.relay=relay; }
        @Scheduled(fixedDelayString="${catering.events.relay-delay:500}") public void run() { relay.publishBatch(); }
    }
}
