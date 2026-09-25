package ru.itmo.highload.catering.common.web;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import static org.assertj.core.api.Assertions.assertThat;

class BlockingRequestsTest {
    @Test void defersWorkMovesTraceAndCleansWorkerAfterFailure() {
        var scheduler = Schedulers.newBoundedElastic(1, 2, "order-blocking-test");
        try {
            var requests = new BlockingRequests(scheduler);
            var invoked = new java.util.concurrent.atomic.AtomicBoolean();
            var result = requests.call(() -> {
                invoked.set(true);
                assertThat(Thread.currentThread().getName()).startsWith("order-blocking-test-");
                assertThat(Schedulers.isInNonBlockingThread()).isFalse();
                assertThat(MDC.get("traceId")).isEqualTo("reactor-trace");
                throw new IllegalStateException("failed work");
            }).contextWrite(context -> context.put("traceId", "reactor-trace"));
            assertThat(invoked).isFalse();
            StepVerifier.create(result).expectError(IllegalStateException.class).verify();
            StepVerifier.create(reactor.core.publisher.Mono.fromCallable(() -> MDC.get("traceId") == null)
                    .subscribeOn(scheduler)).expectNext(true).verifyComplete();
        } finally { scheduler.dispose(); }
    }
    @Test void rejectsWorkWhenBoundedQueueIsFull() throws Exception {
        var scheduler = Schedulers.newBoundedElastic(1, 1, "order-blocking-test");
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            var requests = new BlockingRequests(scheduler);
            requests.call(() -> { started.countDown(); release.await(5, TimeUnit.SECONDS); return 1; }).subscribe();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            requests.call(() -> 2).subscribe();
            StepVerifier.create(requests.call(() -> 3))
                    .expectError(java.util.concurrent.RejectedExecutionException.class).verify(Duration.ofSeconds(2));
        } finally {
            release.countDown();
            scheduler.dispose();
        }
    }
}
