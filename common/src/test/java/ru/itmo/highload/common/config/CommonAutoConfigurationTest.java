package ru.itmo.highload.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.*;
import org.junit.jupiter.api.Test;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConverters;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import reactor.core.scheduler.Scheduler;
import ru.itmo.highload.common.error.*;
import ru.itmo.highload.common.web.BlockingRequests;

class CommonAutoConfigurationTest {
    private final ReactiveWebApplicationContextRunner runner = new ReactiveWebApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withConfiguration(AutoConfigurations.of(CommonWebAutoConfiguration.class,
                    CommonPersistenceAutoConfiguration.class, CommonOpenApiAutoConfiguration.class,
                    CommonFeignAutoConfiguration.class, CommonBlockingAutoConfiguration.class));

    @Test void registersDefaultsWithoutEnablingBlockingWork() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(TraceFilter.class).hasSingleBean(GlobalExceptionHandler.class)
                    .hasSingleBean(PersistenceExceptionHandler.class).hasSingleBean(HttpMessageConverters.class)
                    .doesNotHaveBean(Scheduler.class).doesNotHaveBean(BlockingRequests.class);
            var document = new OpenAPI().paths(new Paths()
                    .addPathItem("/api/v1/example", new PathItem().get(new Operation()))
                    .addPathItem("/internal/example", new PathItem().get(new Operation())));
            context.getBean(OpenApiCustomizer.class).customise(document);
            assertThat(document.getPaths().get("/api/v1/example").getGet().getParameters())
                    .singleElement().satisfies(header -> assertThat(header.getName()).isEqualTo("X-Trace-Id"));
            assertThat(document.getPaths().get("/internal/example").getGet().getParameters()).isNull();
        });
    }

    @Test void optionalIntegrationsAreNotRequiredByAReactiveConsumer() {
        runner.withClassLoader(new FilteredClassLoader("org.springframework.dao", "org.springframework.data",
                "org.springdoc", "io.swagger", "org.springframework.cloud.openfeign"))
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(TraceFilter.class)
                        .doesNotHaveBean(PersistenceExceptionHandler.class).doesNotHaveBean(HttpMessageConverters.class)
                        .doesNotHaveBean("traceIdHeaderCustomizer").doesNotHaveBean(BlockingRequests.class));
    }

    @Test void userBeansOverrideDefaultsWithoutDuplicateRegistrations() {
        var filter = new TraceFilter();
        var converters = new HttpMessageConverters();
        var handler = new GlobalExceptionHandler();
        runner.withBean("customTrace", TraceFilter.class, () -> filter)
                .withBean("customConverters", HttpMessageConverters.class, () -> converters)
                .withBean("customHandler", GlobalExceptionHandler.class, () -> handler)
                .run(context -> {
                    assertThat(context).hasSingleBean(TraceFilter.class).hasSingleBean(HttpMessageConverters.class)
                            .hasSingleBean(GlobalExceptionHandler.class);
                    assertThat(context.getBean(TraceFilter.class)).isSameAs(filter);
                    assertThat(context.getBean(HttpMessageConverters.class)).isSameAs(converters);
                    assertThat(context.getBean(GlobalExceptionHandler.class)).isSameAs(handler);
                });
        runner.withPropertyValues("catering.web.errors.enabled=false")
                .run(context -> assertThat(context).hasSingleBean(TraceFilter.class)
                        .doesNotHaveBean(GlobalExceptionHandler.class));
    }

    @Test void blockingExecutionIsOptInBoundedAndDisposedWithContext() {
        var saved = new java.util.concurrent.atomic.AtomicReference<Scheduler>();
        runner.withPropertyValues("catering.blocking.enabled=true", "catering.blocking.threads=1",
                "catering.blocking.queue-capacity=2", "catering.blocking.thread-name-prefix=common-test")
                .run(context -> {
                    assertThat(context).hasSingleBean(BlockingRequests.class);
                    var scheduler = context.getBean("blockingRequestScheduler", Scheduler.class);
                    saved.set(scheduler);
                    String thread = context.getBean(BlockingRequests.class)
                            .call(() -> Thread.currentThread().getName()).block();
                    assertThat(thread).startsWith("common-test-");
                });
        assertThat(saved.get().isDisposed()).isTrue();
        runner.withPropertyValues("catering.blocking.enabled=true", "catering.blocking.threads=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
