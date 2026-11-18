package ru.itmo.highload.common.testing;

import com.github.dockerjava.api.command.InspectContainerResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.Uuid;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

/** Three real broker/controllers; external listener ports are assigned by Docker. */
public final class KafkaCluster implements AutoCloseable {
    private final Network network = Network.newNetwork();
    private final List<Broker> brokers;
    public KafkaCluster() {
        String cluster = Uuid.randomUuid().toString();
        brokers = IntStream.rangeClosed(1,3).mapToObj(id -> new Broker(id,cluster,network)).toList();
    }
    public KafkaCluster start() {
        try {
            CompletableFuture.allOf(brokers.stream().map(b -> CompletableFuture.runAsync(b::start))
                    .toArray(CompletableFuture[]::new)).join();
            return this;
        } catch (RuntimeException error) { close(); throw error; }
    }
    public String bootstrapServers() {
        return String.join(",", brokers.stream().filter(Broker::isRunning)
                .map(b -> b.getHost()+":"+b.getMappedPort(19092)).toList());
    }
    public Admin admin() {
        return Admin.create(Map.of("bootstrap.servers",bootstrapServers(),"request.timeout.ms","5000","default.api.timeout.ms","30000"));
    }
    public void createTopics() throws Exception {
        try (var admin = admin()) {
            var topics = List.of("order-events","production-events","file-events");
            var definitions = topics.stream().flatMap(t -> java.util.stream.Stream.of(t,t+".DLT"))
                    .map(t -> new NewTopic(t,3,(short)3).configs(Map.of("min.insync.replicas","2"))).toList();
            admin.createTopics(definitions).all().get(45,java.util.concurrent.TimeUnit.SECONDS);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(60))
                    .pollInterval(Duration.ofMillis(300))
                    .ignoreExceptionsMatching(error -> error instanceof java.util.concurrent.ExecutionException
                            && error.getCause() instanceof org.apache.kafka.common.errors.UnknownTopicOrPartitionException)
                    .untilAsserted(() -> {
                        var metadata = admin.describeTopics(definitions.stream().map(NewTopic::name).toList())
                                .allTopicNames().get(10,java.util.concurrent.TimeUnit.SECONDS);
                        org.assertj.core.api.Assertions.assertThat(metadata).hasSize(6);
                        metadata.values().forEach(topic -> topic.partitions().forEach(partition -> {
                            org.assertj.core.api.Assertions.assertThat(partition.leader()).isNotNull();
                            org.assertj.core.api.Assertions.assertThat(partition.leader().id()).isPositive();
                            org.assertj.core.api.Assertions.assertThat(partition.isr()).hasSize(3);
                        }));
                    });
        }
    }
    public void stopBroker(int id) { brokers.get(id-1).stop(); }
    public void stopAll() {
        var docker=org.testcontainers.DockerClientFactory.instance().client();
        CompletableFuture.allOf(brokers.stream().map(b -> CompletableFuture.runAsync(
                () -> docker.stopContainerCmd(b.getContainerId()).withTimeout(20).exec()))
                .toArray(CompletableFuture[]::new)).join();
    }
    public void startAll() {
        var docker=org.testcontainers.DockerClientFactory.instance().client();
        CompletableFuture.allOf(brokers.stream().map(b -> CompletableFuture.runAsync(
                () -> docker.startContainerCmd(b.getContainerId()).exec()))
                .toArray(CompletableFuture[]::new)).join();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(90)).ignoreExceptions().untilAsserted(() -> {
            try(var admin=admin()) {
                var metadata=admin.describeTopics(List.of("order-events","production-events","file-events"))
                        .allTopicNames().get(10,java.util.concurrent.TimeUnit.SECONDS);
                metadata.values().forEach(t -> t.partitions().forEach(p ->
                        org.assertj.core.api.Assertions.assertThat(p.isr()).hasSize(3)));
            }
        });
    }
    @Override public void close() {
        brokers.forEach(b -> { try { b.stop(); } catch (RuntimeException ignored) { } });
        network.close();
    }
    private static final class Broker extends GenericContainer<Broker> {
        private final int id;
        Broker(int id, String cluster, Network network) {
            super("apache/kafka:3.9.1"); this.id=id;
            // Docker otherwise chooses a new random host port on stop/start, invalidating
            // the advertised external listener. Choose a free port once for this fixture.
            addFixedExposedPort(availablePort(),19092);
            withNetwork(network).withNetworkAliases("kafka-"+id)
                    .withEnv("CLUSTER_ID",cluster).withEnv("KAFKA_NODE_ID",Integer.toString(id))
                    .withEnv("KAFKA_PROCESS_ROLES","broker,controller")
                    .withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS","1@kafka-1:9093,2@kafka-2:9093,3@kafka-3:9093")
                    .withEnv("KAFKA_LISTENERS","INTERNAL://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093,EXTERNAL://0.0.0.0:19092")
                    .withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP","INTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT,EXTERNAL:PLAINTEXT")
                    .withEnv("KAFKA_INTER_BROKER_LISTENER_NAME","INTERNAL")
                    .withEnv("KAFKA_CONTROLLER_LISTENER_NAMES","CONTROLLER")
                    .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR","3")
                    .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR","3")
                    .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR","2")
                    .withEnv("KAFKA_DEFAULT_REPLICATION_FACTOR","3")
                    .withEnv("KAFKA_MIN_INSYNC_REPLICAS","2")
                    .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE","false")
                    .withEnv("KAFKA_NUM_NETWORK_THREADS","2").withEnv("KAFKA_NUM_IO_THREADS","2")
                    .withEnv("KAFKA_HEAP_OPTS","-Xms256m -Xmx256m")
                    .withCommand("/bin/sh","-c","while [ ! -f /tmp/listener.sh ]; do sleep 0.1; done; . /tmp/listener.sh; exec /etc/kafka/docker/run")
                    .waitingFor(Wait.forLogMessage(".*Kafka Server started.*",1).withStartupTimeout(Duration.ofMinutes(3)))
                    .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(640L*1024*1024));
        }
        private static int availablePort() {
            try(var socket=new java.net.ServerSocket(0)) { return socket.getLocalPort(); }
            catch(java.io.IOException error) { throw new IllegalStateException("No free Kafka listener port",error); }
        }
        @Override protected void containerIsStarting(InspectContainerResponse info) {
            String listeners="INTERNAL://kafka-"+id+":9092,EXTERNAL://"+getHost()+":"+getMappedPort(19092);
            copyFileToContainer(Transferable.of("export KAFKA_ADVERTISED_LISTENERS='"+listeners+"'\n"),"/tmp/listener.sh");
        }
    }
}
