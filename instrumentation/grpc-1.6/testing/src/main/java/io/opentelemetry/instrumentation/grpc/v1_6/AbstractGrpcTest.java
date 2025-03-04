/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.grpc.v1_6;

import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.assertThat;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.equalTo;
import static io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.satisfies;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import example.GreeterGrpc;
import example.Helloworld;
import io.grpc.BindableService;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.services.ProtoReflectionService;
import io.grpc.reflection.v1alpha.ServerReflectionGrpc;
import io.grpc.reflection.v1alpha.ServerReflectionRequest;
import io.grpc.reflection.v1alpha.ServerReflectionResponse;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.instrumentation.testing.junit.InstrumentationExtension;
import io.opentelemetry.instrumentation.testing.util.ThrowingRunnable;
import io.opentelemetry.sdk.testing.assertj.AttributeAssertion;
import io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.semconv.trace.attributes.SemanticAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.provider.ValueSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractGrpcTest {
  protected static final String CLIENT_REQUEST_METADATA_KEY = "some-client-key";

  protected static final String SERVER_REQUEST_METADATA_KEY = "some-server-key";

  protected abstract ServerBuilder<?> configureServer(ServerBuilder<?> server);

  protected abstract ManagedChannelBuilder<?> configureClient(ManagedChannelBuilder<?> client);

  protected abstract InstrumentationExtension testing();

  protected final Queue<ThrowingRunnable<?>> closer = new ConcurrentLinkedQueue<>();

  @AfterEach
  void tearDown() throws Throwable {
    while (!closer.isEmpty()) {
      closer.poll().run();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"some name", "some other name"})
  void successBlockingStub(String paramName) throws Exception {
    BindableService greeter =
        new GreeterGrpc.GreeterImplBase() {
          @Override
          public void sayHello(
              Helloworld.Request req, StreamObserver<Helloworld.Response> responseObserver) {
            Helloworld.Response reply =
                Helloworld.Response.newBuilder().setMessage("Hello " + req.getName()).build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
          }
        };
    Server server = configureServer(ServerBuilder.forPort(0).addService(greeter)).build().start();
    ManagedChannel channel = createChannel(server);
    closer.add(() -> channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS));
    closer.add(() -> server.shutdownNow().awaitTermination());

    GreeterGrpc.GreeterBlockingStub client = GreeterGrpc.newBlockingStub(channel);

    Helloworld.Response response =
        testing()
            .runWithSpan(
                "parent",
                () -> client.sayHello(Helloworld.Request.newBuilder().setName(paramName).build()));

    assertThat(response.getMessage()).isEqualTo("Hello " + paramName);

    testing()
        .waitAndAssertTraces(
            trace ->
                trace.hasSpansSatisfyingExactly(
                    span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                    span ->
                        span.hasName("example.Greeter/SayHello")
                            .hasKind(SpanKind.CLIENT)
                            .hasParent(trace.getSpan(0))
                            .hasAttributesSatisfyingExactly(
                                addExtraClientAttributes(
                                    equalTo(SemanticAttributes.RPC_SYSTEM, "grpc"),
                                    equalTo(SemanticAttributes.RPC_SERVICE, "example.Greeter"),
                                    equalTo(SemanticAttributes.RPC_METHOD, "SayHello"),
                                    equalTo(
                                        SemanticAttributes.RPC_GRPC_STATUS_CODE,
                                        (long) Status.Code.OK.value()),
                                    equalTo(SemanticAttributes.NET_PEER_NAME, "localhost"),
                                    equalTo(
                                        SemanticAttributes.NET_PEER_PORT, (long) server.getPort())))
                            .hasEventsSatisfyingExactly(
                                event ->
                                    event
                                        .hasName("message")
                                        .hasAttributesSatisfying(
                                            attrs ->
                                                assertThat(attrs)
                                                    .containsOnly(
                                                        entry(
                                                            SemanticAttributes.MESSAGE_TYPE,
                                                            "SENT"),
                                                        entry(SemanticAttributes.MESSAGE_ID, 1L))),
                                event ->
                                    event
                                        .hasName("message")
                                        .hasAttributesSatisfying(
                                            attrs ->
                                                assertThat(attrs)
                                                    .containsOnly(
                                                        entry(
                                                            SemanticAttributes.MESSAGE_TYPE,
                                                            "RECEIVED"),
                                                        entry(SemanticAttributes.MESSAGE_ID, 2L)))),
                    span ->
                        span.hasName("example.Greeter/SayHello")
                            .hasKind(SpanKind.SERVER)
                            .hasParent(trace.getSpan(1))
                            .hasAttributesSatisfyingExactly(
                                equalTo(SemanticAttributes.RPC_SYSTEM, "grpc"),
                                equalTo(SemanticAttributes.RPC_SERVICE, "example.Greeter"),
                                equalTo(SemanticAttributes.RPC_METHOD, "SayHello"),
                                equalTo(
                                    SemanticAttributes.RPC_GRPC_STATUS_CODE,
                                    (long) Status.Code.OK.value()),
                                equalTo(SemanticAttributes.NET_HOST_NAME, "localhost"),
                                equalTo(SemanticAttributes.NET_HOST_PORT, server.getPort()),
                                equalTo(SemanticAttributes.NET_SOCK_PEER_ADDR, "127.0.0.1"),
                                satisfies(
                                    SemanticAttributes.NET_SOCK_PEER_PORT,
                                    val -> assertThat(val).isNotNull()))
                            .hasEventsSatisfyingExactly(
                                event ->
                                    event
                                        .hasName("message")
                                        .hasAttributesSatisfying(
                                            attrs ->
                                                assertThat(attrs)
                                                    .containsOnly(
                                                        entry(
                                                            SemanticAttributes.MESSAGE_TYPE,
                                                            "RECEIVED"),
                                                        entry(SemanticAttributes.MESSAGE_ID, 1L))),
                                event ->
                                    event
                                        .hasName("message")
                                        .hasAttributesSatisfying(
                                            attrs ->
                                                assertThat(attrs)
                                                    .containsOnly(
                                                        entry(
                                                            SemanticAttributes.MESSAGE_TYPE,
                                                            "SENT"),
                                                        entry(
                                                            SemanticAttributes.MESSAGE_ID, 2L))))));
    testing()
        .waitAndAssertMetrics(
            "io.opentelemetry.grpc-1.6",
            "rpc.server.duration",
            metrics ->
                metrics.anySatisfy(
                    metric ->
                        assertThat(metric)
                            .hasUnit("ms")
                            .hasHistogramSatisfying(
                                histogram ->
                                    histogram.hasPointsSatisfying(
                                        point ->
                                            point.hasAttributesSatisfying(
                                                equalTo(
                                                    SemanticAttributes.NET_HOST_NAME, "localhost"),
                                                equalTo(SemanticAttributes.RPC_METHOD, "SayHello"),
                                                equalTo(
                                                    SemanticAttributes.RPC_SERVICE,
                                                    "example.Greeter"),
                                                equalTo(SemanticAttributes.RPC_SYSTEM, "grpc"),
                                                equalTo(
                                                    SemanticAttributes.RPC_GRPC_STATUS_CODE,
                                                    (long) Status.Code.OK.value()))))));
    testing()
        .waitAndAssertMetrics(
            "io.opentelemetry.grpc-1.6",
            "rpc.client.duration",
            metrics ->
                metrics.anySatisfy(
                    metric ->
                        assertThat(metric)
                            .hasUnit("ms")
                            .hasHistogramSatisfying(
                                histogram ->
                                    histogram.hasPointsSatisfying(
                                        point ->
                                            point.hasAttributesSatisfying(
                                                equalTo(
                                                    SemanticAttributes.NET_PEER_NAME, "localhost"),
                                                equalTo(
                                                    SemanticAttributes.NET_PEER_PORT,
                                                    server.getPort()),
                                                equalTo(SemanticAttributes.RPC_METHOD, "SayHello"),
                                                equalTo(
                                                    SemanticAttributes.RPC_SERVICE,
                                                    "example.Greeter"),
                                                equalTo(SemanticAttributes.RPC_SYSTEM, "grpc"),
                                                equalTo(
                                                    SemanticAttributes.RPC_GRPC_STATUS_CODE,
                                                    (long) Status.Code.OK.value()))))));
  }

  @Test
  void listenableFuture() throws Exception {
    BindableService greeter =
        new GreeterGrpc.GreeterImplBase() {
          @Override
          public void sayHello(
              Helloworld.Request req, StreamObserver<Helloworld.Response> responseObserver) {
            Helloworld.Response reply =
                Helloworld.Response.newBuilder().setMessage("Hello " + req.getName()).build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
          }
        };

    Server server = configureServer(ServerBuilder.forPort(0).addService(greeter)).build().start();
    ManagedChannel channel = createChannel(server);
    closer.add(() -> channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS));
    closer.add(() -> server.shutdownNow().awaitTermination());

    GreeterGrpc.GreeterFutureStub client = GreeterGrpc.newFutureStub(channel);

    AtomicReference<Helloworld.Response> response = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    testing()
        .runWithSpan(
            "parent",
            () -> {
              ListenableFuture<Helloworld.Response> future =
                  Futures.transform(
                      client.sayHello(Helloworld.Request.newBuilder().setName("test").build()),
                      resp -> {
                        testing().runWithSpan("child", () -> {});
                        return resp;
                      },
                      MoreExecutors.directExecutor());
              try {
                response.set(Futures.getUnchecked(future));
              } catch (Throwable t) {
                error.set(t);
              }
            });

    assertThat(error).hasValue(null);
    assertThat(response.get().getMessage()).isEqualTo("Hello test");

    testing()
        .waitAndAssertTraces(
            trace ->
                trace.hasSpansSatisfyingExactly(
                    span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                    span ->
                        span.hasName("example.Greeter/SayHello")
                            .hasKind(SpanKind.CLIENT)
                            .hasParent(trace.getSpan(0))
                            .hasAttributesSatisfyingExactly(
                                addExtraClientAttributes(
                                    equalTo(SemanticAttributes.RPC_SYSTEM, "grpc"),
                                    equalTo(SemanticAttributes.RPC_SERVICE, "example.Greeter"),
                                    equalTo(SemanticAttributes.RPC_METHOD, "SayHello"),
                                    equalTo(
                                        SemanticAttributes.RPC_GRPC_STATUS_CODE,
                                        (long) Status.Code.OK.value()),
                                    equalTo(SemanticAttributes.NET_PEER_NAME, "localhost"),
                                    equalTo(
                                        SemanticAttributes.NET_PEER_PORT, (long) server.getPort())))
                            .hasEventsSatisfyingExactly(
                                event ->
                                    event
                                        .hasName("message")
                                        .hasAttributesSatisfying(
                                            attrs ->
                                                assertThat(attrs)
                                                    .containsOnly(
                                                        entry(
                                                            SemanticAttributes.MESSAGE_TYPE,
                                                            "SENT"),
                                                        entry(SemanticAttributes.MESSAGE_ID, 1L))),
                                event ->
                                    event
                                        .hasName("message")
                                        .hasAttributesSatisfying(
                                            attrs ->
                                                assertThat(attrs)
                                                    .containsOnly(
                                                        entry(
                                                            SemanticAttributes.MESSAGE_TYPE,
                                                            "RECEIVED"),
                                                        entry(SemanticAttributes.MESSAGE_ID, 2L)))),
                    span ->
                        span.hasName("example.Greeter/SayHello")
                            .hasKind(SpanKind.SERVER)
                            .hasParent(trace.getSpan(1))
                            .hasAttributesSatisfyingExactly(
                                equalTo(SemanticAttributes.RPC_SYSTEM, "grpc"),
                                equalTo(SemanticAttributes.RPC_SERVICE, "example.Greeter"),
                                equalTo(SemanticAttributes.RPC_METHOD, "SayHello"),
                                equalTo(
                                    SemanticAttributes.RPC_GRPC_STATUS_CODE,
                                    (long) Status.Code.OK.value()),
                                equalTo(SemanticAttributes.NET_HOST_NAME, "localhost"),
                                equalTo(SemanticAttributes.NET_HOST_PORT, server.getPort()),
                                equalTo(SemanticAttributes.NET_SOCK_PEER_ADDR, "127.0.0.1"),
                                satisfies(
                                    SemanticAttributes.NET_SOCK_PEER_PORT,
                                    val -> assertThat(val).isNotNull()))
                            .hasEventsSatisfyingExactly(
                                event ->
                                    event
                                        .hasName("message")
                                        .hasAttributesSatisfying(
                                            attrs ->
                                                assertThat(attrs)
                                                    .containsOnly(
                                                        entry(
                                                            SemanticAttributes.MESSAGE_TYPE,
                                                            "RECEIVED"),
                                                        entry(SemanticAttributes.MESSAGE_ID, 1L))),
                                event ->
                                    event
                                        .hasName("message")
                                        .hasAttributesSatisfying(
                                            attrs ->
                                                assertThat(attrs)
                                                    .containsOnly(
                                                        entry(
                                                            SemanticAttributes.MESSAGE_TYPE,
                                                            "SENT"),
                                                        entry(SemanticAttributes.MESSAGE_ID, 2L)))),
                    span ->
                        span.hasName("child")
                            .hasKind(SpanKind.INTERNAL)
                            .hasParent(trace.getSpan(0))));
    testing()
        .waitAndAssertMetrics(
            "io.opentelemetry.grpc-1.6",
            "rpc.server.duration",
            metrics ->
                metrics.anySatisfy(
                    metric ->
                        assertThat(metric)
                            .hasUnit("ms")
                            .hasHistogramSatisfying(
                                histogram ->
                                    histogram.hasPointsSatisfying(
                                        point ->
                                            point.hasAttributesSatisfying(
                                                equalTo(
                                                    SemanticAttributes.NET_HOST_NAME, "localhost"),
                                                equalTo(SemanticAttributes.RPC_METHOD, "SayHello"),
                                                equalTo(
                                                    SemanticAttributes.RPC_SERVICE,
                                                    "example.Greeter"),
                                                equalTo(SemanticAttributes.RPC_SYSTEM, "grpc"),
                                                equalTo(
                                                    SemanticAttributes.RPC_GRPC_STATUS_CODE,
                                                    (long) Status.Code.OK.value()))))));
    testing()
        .waitAndAssertMetrics(
            "io.opentelemetry.grpc-1.6",
            "rpc.client.duration",
            metrics ->
                metrics.anySatisfy(
                    metric ->
                        assertThat(metric)
                            .hasUnit("ms")
                            .hasHistogramSatisfying(
                                histogram ->
                                    histogram.hasPointsSatisfying(
                                        point ->
                                            point.hasAttributesSatisfying(
                                                equalTo(
                                                    SemanticAttributes.NET_PEER_NAME, "localhost"),
                                                equalTo(
                                                    SemanticAttributes.NET_PEER_PORT,
                                                    server.getPort()),
                                                equalTo(SemanticAttributes.RPC_METHOD, "SayHello"),
                                                equalTo(
                                                    SemanticAttributes.RPC_SERVICE,
                                                    "example.Greeter"),
                                                equalTo(SemanticAttributes.RPC_SYSTEM, "grpc"),
                                                equalTo(
                                                    SemanticAttributes.RPC_GRPC_STATUS_CODE,
                                                    (long) Status.Code.OK.value()))))));
  }

  @Test
  void streamingStub() throws Exception {
    BindableService greeter =
        new GreeterGrpc.GreeterImplBase() {
          @Override
          public void sayHello(
              Helloworld.Request req, StreamObserver<Helloworld.Response> responseObserver) {
            Helloworld.Response reply =
                Helloworld.Response.newBuilder().setMessage("Hello " + req.getName()).build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
          }
        };

    Server server = configureServer(ServerBuilder.forPort(0).addService(greeter)).build().start();
    ManagedChannel channel = createChannel(server);
    closer.add(() -> channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS));
    closer.add(() -> server.shutdownNow().awaitTermination());

    GreeterGrpc.GreeterStub client = GreeterGrpc.newStub(channel);

    AtomicReference<Helloworld.Response> response = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    testing()
        .runWithSpan(
            "parent",
            () ->
                client.sayHello(
                    Helloworld.Request.newBuilder().setName("test").build(),
                    new StreamObserver<Helloworld.Response>() {
                      @Override
                      public void onNext(Helloworld.Response r) {
                        response.set(r);
                      }

                      @Override
                      public void onError(Throwable throwable) {
                        error.set(throwable);
                      }

                      @Override
                      public void onCompleted() {
                        testing().runWithSpan("child", () -> {});
                        latch.countDown();
                      }
                    }));

    latch.await(10, TimeUnit.SECONDS);

    assertThat(error).hasValue(null);
    assertThat(response.get().getMessage()).isEqualTo("Hello test");

    testing()
        .waitAndAssertTraces(
            trace ->
                trace.hasSpansSatisfyingExactly(
                    span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                    span ->
                        span.hasName("example.Greeter/SayHello")
                            .hasKind(SpanKind.CLIENT)
                            .hasParent(trace.getSpan(0))
                            .hasAttributesSatisfyingExactly(
                                addExtraClientAttributes(
                                    equalTo(SemanticAttributes.RPC_SYSTEM, "grpc"),
                                    equalTo(SemanticAttributes.RPC_SERVICE, "example.Greeter"),
                                    equalTo(SemanticAttributes.RPC_METHOD, "SayHello"),
                                    equalTo(
                                        SemanticAttributes.RPC_GRPC_STATUS_CODE,
                                        (long) Status.Code.OK.value()),
                                    equalTo(SemanticAttributes.NET_PEER_NAME, "localhost"),
                                    equalTo(
                                        SemanticAttributes.NET_PEER_PORT, (long) server.getPort())))
                            .hasEventsSatisfyingExactly(
                                event ->
                                    event
                                        .hasName("message")
                                        .hasAttributesSatisfying(
                                            attrs ->
                                                assertThat(attrs)
                                                    .containsOnly(
                                                        entry(
                                                            SemanticAttributes.MESSAGE_TYPE,
                                                            "SENT"),
                                                        entry(SemanticAttributes.MESSAGE_ID, 1L))),
                                event ->
                                    event
                                        .hasName("message")
                                        .hasAttributesSatisfying(
                                            attrs ->
                                                assertThat(attrs)
                                                    .containsOnly(
                                                        entry(
                                                            SemanticAttributes.MESSAGE_TYPE,
                                                            "RECEIVED"),
                                                        entry(SemanticAttributes.MESSAGE_ID, 2L)))),
                    span ->
                        span.hasName("example.Greeter/SayHello")
                            .hasKind(SpanKind.SERVER)
                            .hasParent(trace.getSpan(1))
                            .hasAttributesSatisfyingExactly(
                                equalTo(SemanticAttributes.RPC_SYSTEM, "grpc"),
                                equalTo(SemanticAttributes.RPC_SERVICE, "example.Greeter"),
                                equalTo(SemanticAttributes.RPC_METHOD, "SayHello"),
                                equalTo(
                                    SemanticAttributes.RPC_GRPC_STATUS_CODE,
                                    (long) Status.Code.OK.value()),
                                equalTo(SemanticAttributes.NET_HOST_NAME, "localhost"),
                                equalTo(SemanticAttributes.NET_HOST_PORT, server.getPort()),
                                equalTo(SemanticAttributes.NET_SOCK_PEER_ADDR, "127.0.0.1"),
                                satisfies(
                                    SemanticAttributes.NET_SOCK_PEER_PORT,
                                    val -> assertThat(val).isNotNull()))
                            .hasEventsSatisfyingExactly(
                                event ->
                                    event
                                        .hasName("message")
                                        .hasAttributesSatisfying(
                                            attrs ->
                                                assertThat(attrs)
                                                    .containsOnly(
                                                        entry(
                                                            SemanticAttributes.MESSAGE_TYPE,
                                                            "RECEIVED"),
                                                        entry(SemanticAttributes.MESSAGE_ID, 1L))),
                                event ->
                                    event
                                        .hasName("message")
                                        .hasAttributesSatisfying(
                                            attrs ->
                                                assertThat(attrs)
                                                    .containsOnly(
                                                        entry(
                                                            SemanticAttributes.MESSAGE_TYPE,
                                                            "SENT"),
                                                        entry(SemanticAttributes.MESSAGE_ID, 2L)))),
                    span ->
                        span.hasName("child")
                            .hasKind(SpanKind.INTERNAL)
                            .hasParent(trace.getSpan(0))));
    testing()
        .waitAndAssertMetrics(
            "io.opentelemetry.grpc-1.6",
            "rpc.server.duration",
            metrics ->
                metrics.anySatisfy(
                    metric ->
                        assertThat(metric)
                            .hasUnit("ms")
                            .hasHistogramSatisfying(
                                histogram ->
                                    histogram.hasPointsSatisfying(
                                        point ->
                                            point.hasAttributesSatisfying(
                                                equalTo(
                                                    SemanticAttributes.NET_HOST_NAME, "localhost"),
                                                equalTo(SemanticAttributes.RPC_METHOD, "SayHello"),
                                                equalTo(
                                                    SemanticAttributes.RPC_SERVICE,
                                                    "example.Greeter"),
                                                equalTo(SemanticAttributes.RPC_SYSTEM, "grpc"),
                                                equalTo(
                                                    SemanticAttributes.RPC_GRPC_STATUS_CODE,
                                                    (long) Status.Code.OK.value()))))));
    testing()
        .waitAndAssertMetrics(
            "io.opentelemetry.grpc-1.6",
            "rpc.client.duration",
            metrics ->
                metrics.anySatisfy(
                    metric ->
                        assertThat(metric)
                            .hasUnit("ms")
                            .hasHistogramSatisfying(
                                histogram ->
                                    histogram.hasPointsSatisfying(
                                        point ->
                                            point.hasAttributesSatisfying(
                                                equalTo(
                                                    SemanticAttributes.NET_PEER_NAME, "localhost"),
                                                equalTo(
                                                    SemanticAttributes.NET_PEER_PORT,
                                                    server.getPort()),
                                                equalTo(SemanticAttributes.RPC_METHOD, "SayHello"),
                                                equalTo(
                                                    SemanticAttributes.RPC_SERVICE,
                                                    "example.Greeter"),
                                                equalTo(SemanticAttributes.RPC_SYSTEM, "grpc"),
                                                equalTo(
                                                    SemanticAttributes.RPC_GRPC_STATUS_CODE,
                                                    (long) Status.Code.OK.value()))))));
  }

  @ParameterizedTest
  @ArgumentsSource(ErrorProvider.class)
  void errorReturned(Status status) throws Exception {
    BindableService greeter =
        new GreeterGrpc.GreeterImplBase() {
          @Override
          public void sayHello(
              Helloworld.Request req, StreamObserver<Helloworld.Response> responseObserver) {
            responseObserver.onError(status.asException());
          }
        };
    Server server = configureServer(ServerBuilder.forPort(0).addService(greeter)).build().start();
    ManagedChannel channel = createChannel(server);
    closer.add(() -> channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS));
    closer.add(() -> server.shutdownNow().awaitTermination());

    GreeterGrpc.GreeterBlockingStub client = GreeterGrpc.newBlockingStub(channel);

    assertThatThrownBy(
            () -> client.sayHello(Helloworld.Request.newBuilder().setName("error").build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            t -> {
              assertThat(t.getStatus().getCode()).isEqualTo(status.getCode());
              assertThat(t.getStatus().getDescription()).isEqualTo(status.getDescription());
            });

    testing()
        .waitAndAssertTraces(
            trace ->
                trace.hasSpansSatisfyingExactly(
                    span ->
                        span.hasName("example.Greeter/SayHello")
                            .hasKind(SpanKind.CLIENT)
                            .hasNoParent()
                            .hasStatus(StatusData.error())
                            .hasAttributesSatisfyingExactly(
                                addExtraClientAttributes(
                                    equalTo(SemanticAttributes.RPC_SYSTEM, "grpc"),
                                    equalTo(SemanticAttributes.RPC_SERVICE, "example.Greeter"),
                                    equalTo(SemanticAttributes.RPC_METHOD, "SayHello"),
                                    equalTo(
                                        SemanticAttributes.RPC_GRPC_STATUS_CODE,
                                        (long) status.getCode().value()),
                                    equalTo(SemanticAttributes.NET_PEER_NAME, "localhost"),
                                    equalTo(
                                        SemanticAttributes.NET_PEER_PORT, (long) server.getPort())))
                            .hasEventsSatisfyingExactly(
                                event ->
                                    event
                                        .hasName("message")
                                        .hasAttributesSatisfying(
                                            attrs ->
                                                assertThat(attrs)
                                                    .containsOnly(
                                                        entry(
                                                            SemanticAttributes.MESSAGE_TYPE,
                                                            "SENT"),
                                                        entry(SemanticAttributes.MESSAGE_ID, 1L)))),
                    span ->
                        span.hasName("example.Greeter/SayHello")
                            .hasKind(SpanKind.SERVER)
                            .hasParent(trace.getSpan(0))
                            .hasStatus(StatusData.error())
                            .hasAttributesSatisfyingExactly(
                                equalTo(SemanticAttributes.RPC_SYSTEM, "grpc"),
                                equalTo(SemanticAttributes.RPC_SERVICE, "example.Greeter"),
                                equalTo(SemanticAttributes.RPC_METHOD, "SayHello"),
                                equalTo(
                                    SemanticAttributes.RPC_GRPC_STATUS_CODE,
                                    (long) status.getCode().value()),
                                equalTo(SemanticAttributes.NET_HOST_NAME, "localhost"),
                                equalTo(SemanticAttributes.NET_HOST_PORT, server.getPort()),
                                equalTo(SemanticAttributes.NET_SOCK_PEER_ADDR, "127.0.0.1"),
                                satisfies(
                                    SemanticAttributes.NET_SOCK_PEER_PORT,
                                    val -> assertThat(val).isNotNull()))
                            .hasEventsSatisfying(
                                events -> {
                                  assertThat(events).isNotEmpty();
                                  assertThat(events.get(0))
                                      .hasName("message")
                                      .hasAttributesSatisfying(
                                          attrs ->
                                              assertThat(attrs)
                                                  .containsOnly(
                                                      entry(
                                                          SemanticAttributes.MESSAGE_TYPE,
                                                          "RECEIVED"),
                                                      entry(SemanticAttributes.MESSAGE_ID, 1L)));
                                  if (status.getCause() == null) {
                                    assertThat(events).hasSize(1);
                                  } else {
                                    assertThat(events).hasSize(2);
                                    span.hasException(status.getCause());
                                  }
                                })));
    testing()
        .waitAndAssertMetrics(
            "io.opentelemetry.grpc-1.6",
            "rpc.server.duration",
            metrics ->
                metrics.anySatisfy(
                    metric ->
                        assertThat(metric)
                            .hasUnit("ms")
                            .hasHistogramSatisfying(
                                histogram ->
                                    histogram.hasPointsSatisfying(
                                        point ->
                                            point.hasAttributesSatisfying(
                                                equalTo(
                                                    SemanticAttributes.NET_HOST_NAME, "localhost"),
                                                equalTo(SemanticAttributes.RPC_METHOD, "SayHello"),
                                                equalTo(
                                                    SemanticAttributes.RPC_SERVICE,
                                                    "example.Greeter"),
                                                equalTo(SemanticAttributes.RPC_SYSTEM, "grpc"),
                                                equalTo(
                                                    SemanticAttributes.RPC_GRPC_STATUS_CODE,
                                                    (long) status.getCode().value()))))));
    testing()
        .waitAndAssertMetrics(
            "io.opentelemetry.grpc-1.6",
            "rpc.client.duration",
            metrics ->
                metrics.anySatisfy(
                    metric ->
                        assertThat(metric)
                            .hasUnit("ms")
                            .hasHistogramSatisfying(
                                histogram ->
                                    histogram.hasPointsSatisfying(
                                        point ->
                                            point.hasAttributesSatisfying(
                                                equalTo(
                                                    SemanticAttributes.NET_PEER_NAME, "localhost"),
                                                equalTo(
                                                    SemanticAttributes.NET_PEER_PORT,
                                                    server.getPort()),
                                                equalTo(SemanticAttributes.RPC_METHOD, "SayHello"),
                                                equalTo(
                                                    SemanticAttributes.RPC_SERVICE,
                                                    "example.Greeter"),
                                                equalTo(SemanticAttributes.RPC_SYSTEM, "grpc"),
                                                equalTo(
                                                    SemanticAttributes.RPC_GRPC_STATUS_CODE,
                                                    (long) status.getCode().value()))))));
  }

  @ParameterizedTest
  @ArgumentsSource(ErrorProvider.class)
  void errorThrown(Status status) throws Exception {
    BindableService greeter =
        new GreeterGrpc.GreeterImplBase() {
          @Override
          public void sayHello(
              Helloworld.Request req, StreamObserver<Helloworld.Response> responseObserver) {
            throw status.asRuntimeException();
          }
        };
    Server server = configureServer(ServerBuilder.forPort(0).addService(greeter)).build().start();
    ManagedChannel channel = createChannel(server);
    closer.add(() -> channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS));
    closer.add(() -> server.shutdownNow().awaitTermination());

    GreeterGrpc.GreeterBlockingStub client = GreeterGrpc.newBlockingStub(channel);

    assertThatThrownBy(
            () -> client.sayHello(Helloworld.Request.newBuilder().setName("error").build()))
        .isInstanceOfSatisfying(
            StatusRuntimeException.class,
            t -> {
              // gRPC doesn't appear to propagate server exceptions that are thrown, not onError.
              assertThat(t.getStatus().getCode()).isEqualTo(Status.UNKNOWN.getCode());
              assertThat(t.getStatus().getDescription()).isNull();
            });

    testing()
        .waitAndAssertTraces(
            trace ->
                trace.hasSpansSatisfyingExactly(
                    span ->
                        // NB: Exceptions thrown on the server don't appear to be propagated to the
                        // client, at
                        // least for the version we test against, so the client gets an UNKNOWN
                        // status and the server
                        // doesn't record one at all.
                        span.hasName("example.Greeter/SayHello")
                            .hasKind(SpanKind.CLIENT)
                            .hasNoParent()
                            .hasStatus(StatusData.error())
                            .hasAttributesSatisfyingExactly(
                                addExtraClientAttributes(
                                    equalTo(SemanticAttributes.RPC_SYSTEM, "grpc"),
                                    equalTo(SemanticAttributes.RPC_SERVICE, "example.Greeter"),
                                    equalTo(SemanticAttributes.RPC_METHOD, "SayHello"),
                                    equalTo(
                                        SemanticAttributes.RPC_GRPC_STATUS_CODE,
                                        (long) Status.UNKNOWN.getCode().value()),
                                    equalTo(SemanticAttributes.NET_PEER_NAME, "localhost"),
                                    equalTo(
                                        SemanticAttributes.NET_PEER_PORT, (long) server.getPort())))
                            .hasEventsSatisfyingExactly(
                                event ->
                                    event
                                        .hasName("message")
                                        .hasAttributesSatisfying(
                                            attrs ->
                                                assertThat(attrs)
                                                    .containsOnly(
                                                        entry(
                                                            SemanticAttributes.MESSAGE_TYPE,
                                                            "SENT"),
                                                        entry(SemanticAttributes.MESSAGE_ID, 1L)))),
                    span ->
                        span.hasName("example.Greeter/SayHello")
                            .hasKind(SpanKind.SERVER)
                            .hasParent(trace.getSpan(0))
                            .hasStatus(StatusData.error())
                            .hasAttributesSatisfyingExactly(
                                equalTo(SemanticAttributes.RPC_SYSTEM, "grpc"),
                                equalTo(SemanticAttributes.RPC_SERVICE, "example.Greeter"),
                                equalTo(SemanticAttributes.RPC_METHOD, "SayHello"),
                                equalTo(
                                    SemanticAttributes.RPC_GRPC_STATUS_CODE,
                                    (long) Status.Code.UNKNOWN.value()),
                                equalTo(SemanticAttributes.NET_HOST_NAME, "localhost"),
                                equalTo(SemanticAttributes.NET_HOST_PORT, server.getPort()),
                                equalTo(SemanticAttributes.NET_SOCK_PEER_ADDR, "127.0.0.1"),
                                satisfies(
                                    SemanticAttributes.NET_SOCK_PEER_PORT,
                                    val -> assertThat(val).isNotNull()))
                            .hasEventsSatisfying(
                                events -> {
                                  assertThat(events).hasSize(2);
                                  assertThat(events.get(0))
                                      .hasName("message")
                                      .hasAttributesSatisfying(
                                          attrs ->
                                              assertThat(attrs)
                                                  .containsOnly(
                                                      entry(
                                                          SemanticAttributes.MESSAGE_TYPE,
                                                          "RECEIVED"),
                                                      entry(SemanticAttributes.MESSAGE_ID, 1L)));
                                  span.hasException(status.asRuntimeException());
                                })));
    testing()
        .waitAndAssertMetrics(
            "io.opentelemetry.grpc-1.6",
            "rpc.server.duration",
            metrics ->
                metrics.anySatisfy(
                    metric ->
                        assertThat(metric)
                            .hasUnit("ms")
                            .hasHistogramSatisfying(
                                histogram ->
                                    histogram.hasPointsSatisfying(
                                        point ->
                                            point.hasAttributesSatisfying(
                                                equalTo(
                                                    SemanticAttributes.NET_HOST_NAME, "localhost"),
                                                equalTo(SemanticAttributes.RPC_METHOD, "SayHello"),
                                                equalTo(
                                                    SemanticAttributes.RPC_SERVICE,
                                                    "example.Greeter"),
                                                equalTo(SemanticAttributes.RPC_SYSTEM, "grpc"),
                                                equalTo(
                                                    SemanticAttributes.RPC_GRPC_STATUS_CODE,
                                                    (long) Status.Code.UNKNOWN.value()))))));
    testing()
        .waitAndAssertMetrics(
            "io.opentelemetry.grpc-1.6",
            "rpc.client.duration",
            metrics ->
                metrics.anySatisfy(
                    metric ->
                        assertThat(metric)
                            .hasUnit("ms")
                            .hasHistogramSatisfying(
                                histogram ->
                                    histogram.hasPointsSatisfying(
                                        point ->
                                            point.hasAttributesSatisfying(
                                                equalTo(
                                                    SemanticAttributes.NET_PEER_NAME, "localhost"),
                                                equalTo(
                                                    SemanticAttributes.NET_PEER_PORT,
                                                    server.getPort()),
                                                equalTo(SemanticAttributes.RPC_METHOD, "SayHello"),
                                                equalTo(
                                                    SemanticAttributes.RPC_SERVICE,
                                                    "example.Greeter"),
                                                equalTo(SemanticAttributes.RPC_SYSTEM, "grpc"),
                                                equalTo(
                                                    SemanticAttributes.RPC_GRPC_STATUS_CODE,
                                                    (long) Status.Code.UNKNOWN.value()))))));
  }

  static class ErrorProvider implements ArgumentsProvider {
    @Override
    public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
      return Stream.of(
          arguments(Status.UNKNOWN.withCause(new RuntimeException("some error"))),
          arguments(Status.PERMISSION_DENIED.withCause(new RuntimeException("some error"))),
          arguments(Status.UNIMPLEMENTED.withCause(new RuntimeException("some error"))),
          arguments(Status.UNKNOWN.withDescription("some description")),
          arguments(Status.PERMISSION_DENIED.withDescription("some description")),
          arguments(Status.UNIMPLEMENTED.withDescription("some description")));
    }
  }

  @Test
  void userContextPreserved() throws Exception {
    Context.Key<String> key = Context.key("cat");
    BindableService greeter =
        new GreeterGrpc.GreeterImplBase() {
          @Override
          public void sayHello(
              Helloworld.Request req, StreamObserver<Helloworld.Response> responseObserver) {
            if (!key.get().equals("meow")) {
              responseObserver.onError(new AssertionError("context not preserved"));
              return;
            }
            if (!Span.fromContext(io.opentelemetry.context.Context.current())
                .getSpanContext()
                .isValid()) {
              responseObserver.onError(new AssertionError("span not attached"));
              return;
            }
            Helloworld.Response reply =
                Helloworld.Response.newBuilder().setMessage("Hello " + req.getName()).build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
          }
        };
    Server server =
        configureServer(
                ServerBuilder.forPort(0)
                    .addService(greeter)
                    .intercept(
                        new ServerInterceptor() {
                          @Override
                          public <REQ, RESP> ServerCall.Listener<REQ> interceptCall(
                              ServerCall<REQ, RESP> call,
                              Metadata headers,
                              ServerCallHandler<REQ, RESP> next) {
                            if (!Span.fromContext(io.opentelemetry.context.Context.current())
                                .getSpanContext()
                                .isValid()) {
                              throw new AssertionError("span not attached in server interceptor");
                            }
                            Context ctx = Context.current().withValue(key, "meow");
                            return Contexts.interceptCall(ctx, call, headers, next);
                          }
                        }))
            .build()
            .start();
    ManagedChannel channel =
        createChannel(
            configureClient(ManagedChannelBuilder.forAddress("localhost", server.getPort()))
                .intercept(
                    new ClientInterceptor() {
                      @Override
                      public <REQ, RESP> ClientCall<REQ, RESP> interceptCall(
                          MethodDescriptor<REQ, RESP> method,
                          CallOptions callOptions,
                          Channel next) {
                        if (!Span.fromContext(io.opentelemetry.context.Context.current())
                            .getSpanContext()
                            .isValid()) {
                          throw new AssertionError("span not attached in client interceptor");
                        }
                        Context ctx = Context.current().withValue(key, "meow");
                        Context oldCtx = ctx.attach();
                        try {
                          return next.newCall(method, callOptions);
                        } finally {
                          ctx.detach(oldCtx);
                        }
                      }
                    }));
    closer.add(() -> channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS));
    closer.add(() -> server.shutdownNow().awaitTermination());

    GreeterGrpc.GreeterStub client = GreeterGrpc.newStub(channel);

    AtomicReference<Helloworld.Response> response = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    testing()
        .runWithSpan(
            "parent",
            () ->
                client.sayHello(
                    Helloworld.Request.newBuilder().setName("test").build(),
                    new StreamObserver<Helloworld.Response>() {
                      @Override
                      public void onNext(Helloworld.Response r) {
                        if (!key.get().equals("meow")) {
                          error.set(new AssertionError("context not preserved"));
                          return;
                        }
                        if (!Span.fromContext(io.opentelemetry.context.Context.current())
                            .getSpanContext()
                            .isValid()) {
                          error.set(new AssertionError("span not attached"));
                          return;
                        }
                        response.set(r);
                      }

                      @Override
                      public void onError(Throwable throwable) {
                        error.set(throwable);
                      }

                      @Override
                      public void onCompleted() {
                        latch.countDown();
                      }
                    }));

    latch.await(10, TimeUnit.SECONDS);

    assertThat(error).hasValue(null);
    assertThat(response.get().getMessage()).isEqualTo("Hello test");

    testing()
        .waitAndAssertTraces(
            trace ->
                trace.hasSpansSatisfyingExactly(
                    span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                    span ->
                        span.hasName("example.Greeter/SayHello")
                            .hasKind(SpanKind.CLIENT)
                            .hasParent(trace.getSpan(0))
                            .hasAttributesSatisfyingExactly(
                                addExtraClientAttributes(
                                    equalTo(SemanticAttributes.RPC_SYSTEM, "grpc"),
                                    equalTo(SemanticAttributes.RPC_SERVICE, "example.Greeter"),
                                    equalTo(SemanticAttributes.RPC_METHOD, "SayHello"),
                                    equalTo(
                                        SemanticAttributes.RPC_GRPC_STATUS_CODE,
                                        (long) Status.Code.OK.value()),
                                    equalTo(SemanticAttributes.NET_PEER_NAME, "localhost"),
                                    equalTo(
                                        SemanticAttributes.NET_PEER_PORT, (long) server.getPort())))
                            .hasEventsSatisfyingExactly(
                                event ->
                                    event
                                        .hasName("message")
                                        .hasAttributesSatisfying(
                                            attrs ->
                                                assertThat(attrs)
                                                    .containsOnly(
                                                        entry(
                                                            SemanticAttributes.MESSAGE_TYPE,
                                                            "SENT"),
                                                        entry(SemanticAttributes.MESSAGE_ID, 1L))),
                                event ->
                                    event
                                        .hasName("message")
                                        .hasAttributesSatisfying(
                                            attrs ->
                                                assertThat(attrs)
                                                    .containsOnly(
                                                        entry(
                                                            SemanticAttributes.MESSAGE_TYPE,
                                                            "RECEIVED"),
                                                        entry(SemanticAttributes.MESSAGE_ID, 2L)))),
                    span ->
                        span.hasName("example.Greeter/SayHello")
                            .hasKind(SpanKind.SERVER)
                            .hasParent(trace.getSpan(1))
                            .hasAttributesSatisfyingExactly(
                                equalTo(SemanticAttributes.RPC_SYSTEM, "grpc"),
                                equalTo(SemanticAttributes.RPC_SERVICE, "example.Greeter"),
                                equalTo(SemanticAttributes.RPC_METHOD, "SayHello"),
                                equalTo(
                                    SemanticAttributes.RPC_GRPC_STATUS_CODE,
                                    (long) Status.Code.OK.value()),
                                equalTo(SemanticAttributes.NET_HOST_NAME, "localhost"),
                                equalTo(SemanticAttributes.NET_HOST_PORT, server.getPort()),
                                equalTo(SemanticAttributes.NET_SOCK_PEER_ADDR, "127.0.0.1"),
                                satisfies(
                                    SemanticAttributes.NET_SOCK_PEER_PORT,
                                    val -> assertThat(val).isNotNull()))
                            .hasEventsSatisfyingExactly(
                                event ->
                                    event
                                        .hasName("message")
                                        .hasAttributesSatisfying(
                                            attrs ->
                                                assertThat(attrs)
                                                    .containsOnly(
                                                        entry(
                                                            SemanticAttributes.MESSAGE_TYPE,
                                                            "RECEIVED"),
                                                        entry(SemanticAttributes.MESSAGE_ID, 1L))),
                                event ->
                                    event
                                        .hasName("message")
                                        .hasAttributesSatisfying(
                                            attrs ->
                                                assertThat(attrs)
                                                    .containsOnly(
                                                        entry(
                                                            SemanticAttributes.MESSAGE_TYPE,
                                                            "SENT"),
                                                        entry(
                                                            SemanticAttributes.MESSAGE_ID, 2L))))));
  }

  @Test
  void clientErrorThrown() throws Exception {
    BindableService greeter =
        new GreeterGrpc.GreeterImplBase() {
          @Override
          public void sayMultipleHello(
              Helloworld.Request request, StreamObserver<Helloworld.Response> responseObserver) {
            // Send a response but don't complete so client can fail itself
            responseObserver.onNext(Helloworld.Response.getDefaultInstance());
          }
        };

    Server server = configureServer(ServerBuilder.forPort(0).addService(greeter)).build().start();
    ManagedChannel channel = createChannel(server);
    closer.add(() -> channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS));
    closer.add(() -> server.shutdownNow().awaitTermination());

    GreeterGrpc.GreeterStub client = GreeterGrpc.newStub(channel);

    IllegalStateException thrown = new IllegalStateException("illegal");
    AtomicReference<Throwable> error = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    testing()
        .runWithSpan(
            "parent",
            () ->
                client.sayMultipleHello(
                    Helloworld.Request.newBuilder().setName("test").build(),
                    new StreamObserver<Helloworld.Response>() {
                      @Override
                      public void onNext(Helloworld.Response r) {
                        throw thrown;
                      }

                      @Override
                      public void onError(Throwable throwable) {
                        error.set(throwable);
                        latch.countDown();
                      }

                      @Override
                      public void onCompleted() {
                        testing().runWithSpan("child", () -> {});
                        latch.countDown();
                      }
                    }));

    latch.await(10, TimeUnit.SECONDS);

    assertThat(error.get()).isNotNull();

    testing()
        .waitAndAssertTraces(
            trace ->
                trace.hasSpansSatisfyingExactly(
                    span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                    span ->
                        span.hasName("example.Greeter/SayMultipleHello")
                            .hasKind(SpanKind.CLIENT)
                            .hasParent(trace.getSpan(0))
                            .hasStatus(StatusData.error())
                            .hasAttributesSatisfyingExactly(
                                addExtraClientAttributes(
                                    equalTo(SemanticAttributes.RPC_SYSTEM, "grpc"),
                                    equalTo(SemanticAttributes.RPC_SERVICE, "example.Greeter"),
                                    equalTo(SemanticAttributes.RPC_METHOD, "SayMultipleHello"),
                                    equalTo(
                                        SemanticAttributes.RPC_GRPC_STATUS_CODE,
                                        (long) Status.Code.CANCELLED.value()),
                                    equalTo(SemanticAttributes.NET_PEER_NAME, "localhost"),
                                    equalTo(
                                        SemanticAttributes.NET_PEER_PORT, (long) server.getPort())))
                            .hasEventsSatisfying(
                                events -> {
                                  assertThat(events).hasSize(3);
                                  assertThat(events.get(0))
                                      .hasName("message")
                                      .hasAttributesSatisfying(
                                          attrs ->
                                              assertThat(attrs)
                                                  .containsOnly(
                                                      entry(
                                                          SemanticAttributes.MESSAGE_TYPE, "SENT"),
                                                      entry(SemanticAttributes.MESSAGE_ID, 1L)));
                                  assertThat(events.get(1))
                                      .hasName("message")
                                      .hasAttributesSatisfying(
                                          attrs ->
                                              assertThat(attrs)
                                                  .containsOnly(
                                                      entry(
                                                          SemanticAttributes.MESSAGE_TYPE,
                                                          "RECEIVED"),
                                                      entry(SemanticAttributes.MESSAGE_ID, 2L)));
                                  span.hasException(thrown);
                                }),
                    span ->
                        span.hasName("example.Greeter/SayMultipleHello")
                            .hasKind(SpanKind.SERVER)
                            .hasParent(trace.getSpan(1))
                            .hasAttributesSatisfyingExactly(
                                equalTo(SemanticAttributes.RPC_SYSTEM, "grpc"),
                                equalTo(SemanticAttributes.RPC_SERVICE, "example.Greeter"),
                                equalTo(SemanticAttributes.RPC_METHOD, "SayMultipleHello"),
                                equalTo(
                                    SemanticAttributes.RPC_GRPC_STATUS_CODE,
                                    (long) Status.Code.CANCELLED.value()),
                                equalTo(SemanticAttributes.NET_HOST_NAME, "localhost"),
                                equalTo(SemanticAttributes.NET_HOST_PORT, server.getPort()),
                                equalTo(SemanticAttributes.NET_SOCK_PEER_ADDR, "127.0.0.1"),
                                satisfies(
                                    SemanticAttributes.NET_SOCK_PEER_PORT,
                                    val -> assertThat(val).isNotNull()))
                            .hasEventsSatisfyingExactly(
                                event ->
                                    event
                                        .hasName("message")
                                        .hasAttributesSatisfying(
                                            attrs ->
                                                assertThat(attrs)
                                                    .containsOnly(
                                                        entry(
                                                            SemanticAttributes.MESSAGE_TYPE,
                                                            "RECEIVED"),
                                                        entry(SemanticAttributes.MESSAGE_ID, 1L))),
                                event ->
                                    event
                                        .hasName("message")
                                        .hasAttributesSatisfying(
                                            attrs ->
                                                assertThat(attrs)
                                                    .containsOnly(
                                                        entry(
                                                            SemanticAttributes.MESSAGE_TYPE,
                                                            "SENT"),
                                                        entry(
                                                            SemanticAttributes.MESSAGE_ID, 2L))))));
  }

  @Test
  void reflectionService() throws Exception {
    Server server =
        configureServer(ServerBuilder.forPort(0).addService(ProtoReflectionService.newInstance()))
            .build()
            .start();
    ManagedChannel channel = createChannel(server);
    closer.add(() -> channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS));
    closer.add(() -> server.shutdownNow().awaitTermination());

    ServerReflectionGrpc.ServerReflectionStub client = ServerReflectionGrpc.newStub(channel);

    AtomicReference<ServerReflectionResponse> response = new AtomicReference<>();
    AtomicReference<Throwable> error = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);

    StreamObserver<ServerReflectionRequest> request =
        client.serverReflectionInfo(
            new StreamObserver<ServerReflectionResponse>() {
              @Override
              public void onNext(ServerReflectionResponse serverReflectionResponse) {
                response.set(serverReflectionResponse);
              }

              @Override
              public void onError(Throwable throwable) {
                error.set(throwable);
                latch.countDown();
              }

              @Override
              public void onCompleted() {
                latch.countDown();
              }
            });

    request.onNext(
        ServerReflectionRequest.newBuilder()
            .setListServices("The content will not be checked?")
            .build());
    request.onCompleted();

    latch.await(10, TimeUnit.SECONDS);

    assertThat(error).hasValue(null);
    assertThat(response.get().getListServicesResponse().getService(0).getName())
        .isEqualTo("grpc.reflection.v1alpha.ServerReflection");

    testing()
        .waitAndAssertTraces(
            trace ->
                trace.hasSpansSatisfyingExactly(
                    span ->
                        span.hasName(
                                "grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo")
                            .hasKind(SpanKind.CLIENT)
                            .hasNoParent()
                            .hasAttributesSatisfyingExactly(
                                addExtraClientAttributes(
                                    equalTo(SemanticAttributes.RPC_SYSTEM, "grpc"),
                                    equalTo(
                                        SemanticAttributes.RPC_SERVICE,
                                        "grpc.reflection.v1alpha.ServerReflection"),
                                    equalTo(SemanticAttributes.RPC_METHOD, "ServerReflectionInfo"),
                                    equalTo(
                                        SemanticAttributes.RPC_GRPC_STATUS_CODE,
                                        (long) Status.Code.OK.value()),
                                    equalTo(SemanticAttributes.NET_PEER_NAME, "localhost"),
                                    equalTo(
                                        SemanticAttributes.NET_PEER_PORT, (long) server.getPort())))
                            .hasEventsSatisfyingExactly(
                                event ->
                                    event
                                        .hasName("message")
                                        .hasAttributesSatisfying(
                                            attrs ->
                                                assertThat(attrs)
                                                    .containsOnly(
                                                        entry(
                                                            SemanticAttributes.MESSAGE_TYPE,
                                                            "SENT"),
                                                        entry(SemanticAttributes.MESSAGE_ID, 1L))),
                                event ->
                                    event
                                        .hasName("message")
                                        .hasAttributesSatisfying(
                                            attrs ->
                                                assertThat(attrs)
                                                    .containsOnly(
                                                        entry(
                                                            SemanticAttributes.MESSAGE_TYPE,
                                                            "RECEIVED"),
                                                        entry(SemanticAttributes.MESSAGE_ID, 2L)))),
                    span ->
                        span.hasName(
                                "grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo")
                            .hasKind(SpanKind.SERVER)
                            .hasParent(trace.getSpan(0))
                            .hasAttributesSatisfyingExactly(
                                equalTo(SemanticAttributes.RPC_SYSTEM, "grpc"),
                                equalTo(
                                    SemanticAttributes.RPC_SERVICE,
                                    "grpc.reflection.v1alpha.ServerReflection"),
                                equalTo(SemanticAttributes.RPC_METHOD, "ServerReflectionInfo"),
                                equalTo(
                                    SemanticAttributes.RPC_GRPC_STATUS_CODE,
                                    (long) Status.Code.OK.value()),
                                equalTo(SemanticAttributes.NET_HOST_NAME, "localhost"),
                                equalTo(SemanticAttributes.NET_HOST_PORT, server.getPort()),
                                equalTo(SemanticAttributes.NET_SOCK_PEER_ADDR, "127.0.0.1"),
                                satisfies(
                                    SemanticAttributes.NET_SOCK_PEER_PORT,
                                    val -> assertThat(val).isNotNull()))
                            .hasEventsSatisfyingExactly(
                                event ->
                                    event
                                        .hasName("message")
                                        .hasAttributesSatisfying(
                                            attrs ->
                                                assertThat(attrs)
                                                    .containsOnly(
                                                        entry(
                                                            SemanticAttributes.MESSAGE_TYPE,
                                                            "RECEIVED"),
                                                        entry(SemanticAttributes.MESSAGE_ID, 1L))),
                                event ->
                                    event
                                        .hasName("message")
                                        .hasAttributesSatisfying(
                                            attrs ->
                                                assertThat(attrs)
                                                    .containsOnly(
                                                        entry(
                                                            SemanticAttributes.MESSAGE_TYPE,
                                                            "SENT"),
                                                        entry(
                                                            SemanticAttributes.MESSAGE_ID, 2L))))));
  }

  @Test
  void reuseBuilders() throws Exception {
    BindableService greeter =
        new GreeterGrpc.GreeterImplBase() {
          @Override
          public void sayHello(
              Helloworld.Request req, StreamObserver<Helloworld.Response> responseObserver) {
            Helloworld.Response reply =
                Helloworld.Response.newBuilder().setMessage("Hello " + req.getName()).build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
          }
        };
    ServerBuilder<?> serverBuilder = configureServer(ServerBuilder.forPort(0).addService(greeter));
    // Multiple calls to build on same builder
    serverBuilder.build();
    Server server = serverBuilder.build().start();
    ManagedChannelBuilder<?> channelBuilder =
        configureClient(ManagedChannelBuilder.forAddress("localhost", server.getPort()));
    usePlainText(channelBuilder);
    // Multiple calls to build on the same builder
    channelBuilder.build();
    ManagedChannel channel = channelBuilder.build();
    closer.add(() -> channel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS));
    closer.add(() -> server.shutdownNow().awaitTermination());

    GreeterGrpc.GreeterBlockingStub client = GreeterGrpc.newBlockingStub(channel);

    Helloworld.Response response =
        testing()
            .runWithSpan(
                "parent",
                () -> client.sayHello(Helloworld.Request.newBuilder().setName("test").build()));

    assertThat(response.getMessage()).isEqualTo("Hello test");

    testing()
        .waitAndAssertTraces(
            trace ->
                trace.hasSpansSatisfyingExactly(
                    span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                    span ->
                        span.hasName("example.Greeter/SayHello")
                            .hasKind(SpanKind.CLIENT)
                            .hasParent(trace.getSpan(0))
                            .hasAttributesSatisfyingExactly(
                                addExtraClientAttributes(
                                    equalTo(SemanticAttributes.RPC_SYSTEM, "grpc"),
                                    equalTo(SemanticAttributes.RPC_SERVICE, "example.Greeter"),
                                    equalTo(SemanticAttributes.RPC_METHOD, "SayHello"),
                                    equalTo(
                                        SemanticAttributes.RPC_GRPC_STATUS_CODE,
                                        (long) Status.Code.OK.value()),
                                    equalTo(SemanticAttributes.NET_PEER_NAME, "localhost"),
                                    equalTo(
                                        SemanticAttributes.NET_PEER_PORT, (long) server.getPort())))
                            .hasEventsSatisfyingExactly(
                                event ->
                                    event
                                        .hasName("message")
                                        .hasAttributesSatisfying(
                                            attrs ->
                                                assertThat(attrs)
                                                    .containsOnly(
                                                        entry(
                                                            SemanticAttributes.MESSAGE_TYPE,
                                                            "SENT"),
                                                        entry(SemanticAttributes.MESSAGE_ID, 1L))),
                                event ->
                                    event
                                        .hasName("message")
                                        .hasAttributesSatisfying(
                                            attrs ->
                                                assertThat(attrs)
                                                    .containsOnly(
                                                        entry(
                                                            SemanticAttributes.MESSAGE_TYPE,
                                                            "RECEIVED"),
                                                        entry(SemanticAttributes.MESSAGE_ID, 2L)))),
                    span ->
                        span.hasName("example.Greeter/SayHello")
                            .hasKind(SpanKind.SERVER)
                            .hasParent(trace.getSpan(1))
                            .hasAttributesSatisfyingExactly(
                                equalTo(SemanticAttributes.RPC_SYSTEM, "grpc"),
                                equalTo(SemanticAttributes.RPC_SERVICE, "example.Greeter"),
                                equalTo(SemanticAttributes.RPC_METHOD, "SayHello"),
                                equalTo(
                                    SemanticAttributes.RPC_GRPC_STATUS_CODE,
                                    (long) Status.Code.OK.value()),
                                equalTo(SemanticAttributes.NET_HOST_NAME, "localhost"),
                                equalTo(SemanticAttributes.NET_HOST_PORT, server.getPort()),
                                equalTo(SemanticAttributes.NET_SOCK_PEER_ADDR, "127.0.0.1"),
                                satisfies(
                                    SemanticAttributes.NET_SOCK_PEER_PORT,
                                    val -> assertThat(val).isNotNull()))
                            .hasEventsSatisfyingExactly(
                                event ->
                                    event
                                        .hasName("message")
                                        .hasAttributesSatisfying(
                                            attrs ->
                                                assertThat(attrs)
                                                    .containsOnly(
                                                        entry(
                                                            SemanticAttributes.MESSAGE_TYPE,
                                                            "RECEIVED"),
                                                        entry(SemanticAttributes.MESSAGE_ID, 1L))),
                                event ->
                                    event
                                        .hasName("message")
                                        .hasAttributesSatisfying(
                                            attrs ->
                                                assertThat(attrs)
                                                    .containsOnly(
                                                        entry(
                                                            SemanticAttributes.MESSAGE_TYPE,
                                                            "SENT"),
                                                        entry(
                                                            SemanticAttributes.MESSAGE_ID, 2L))))));
  }

  // Regression test for
  // https://github.com/open-telemetry/opentelemetry-java-instrumentation/issues/4169
  @Test
  void clientCallAfterServerCompleted() throws Exception {
    Server backend =
        configureServer(
                ServerBuilder.forPort(0)
                    .addService(
                        new GreeterGrpc.GreeterImplBase() {
                          @Override
                          public void sayHello(
                              Helloworld.Request request,
                              StreamObserver<Helloworld.Response> responseObserver) {
                            responseObserver.onNext(
                                Helloworld.Response.newBuilder()
                                    .setMessage(request.getName())
                                    .build());
                            responseObserver.onCompleted();
                          }
                        }))
            .build()
            .start();
    ManagedChannel backendChannel = createChannel(backend);
    closer.add(() -> backendChannel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS));
    closer.add(() -> backend.shutdownNow().awaitTermination());
    GreeterGrpc.GreeterBlockingStub backendStub = GreeterGrpc.newBlockingStub(backendChannel);

    // This executor does not propagate context without the javaagent available.
    ExecutorService executor = Executors.newFixedThreadPool(1);
    closer.add(executor::shutdownNow);

    CountDownLatch clientCallDone = new CountDownLatch(1);
    AtomicReference<Throwable> error = new AtomicReference<>();

    Server frontend =
        configureServer(
                ServerBuilder.forPort(0)
                    .addService(
                        new GreeterGrpc.GreeterImplBase() {
                          @Override
                          public void sayHello(
                              Helloworld.Request request,
                              StreamObserver<Helloworld.Response> responseObserver) {
                            responseObserver.onNext(
                                Helloworld.Response.newBuilder()
                                    .setMessage(request.getName())
                                    .build());
                            responseObserver.onCompleted();

                            executor.execute(
                                () -> {
                                  try {
                                    backendStub.sayHello(request);
                                  } catch (Throwable t) {
                                    error.set(t);
                                  }
                                  clientCallDone.countDown();
                                });
                          }
                        }))
            .build()
            .start();
    ManagedChannel frontendChannel = createChannel(frontend);
    closer.add(() -> frontendChannel.shutdownNow().awaitTermination(10, TimeUnit.SECONDS));
    closer.add(() -> frontend.shutdownNow().awaitTermination());

    GreeterGrpc.GreeterBlockingStub frontendStub = GreeterGrpc.newBlockingStub(frontendChannel);
    frontendStub.sayHello(Helloworld.Request.newBuilder().setName("test").build());

    // We don't assert on telemetry - the intention of this test is to verify that adding
    // instrumentation, either as
    // library or javaagent, does not cause exceptions in the business logic. The produced telemetry
    // will be different
    // for the two cases due to lack of context propagation in the library case, but that isn't what
    // we're testing here.

    clientCallDone.await(10, TimeUnit.SECONDS);

    assertThat(error).hasValue(null);
  }

  @Test
  void setCapturedRequestMetadata() throws Exception {
    String metadataAttributePrefix = "rpc.grpc.request.metadata.";
    AttributeKey<List<String>> clientAttributeKey =
        AttributeKey.stringArrayKey(metadataAttributePrefix + CLIENT_REQUEST_METADATA_KEY);
    AttributeKey<List<String>> serverAttributeKey =
        AttributeKey.stringArrayKey(metadataAttributePrefix + SERVER_REQUEST_METADATA_KEY);
    String serverMetadataValue = "server-value";
    String clientMetadataValue = "client-value";

    BindableService greeter =
        new GreeterGrpc.GreeterImplBase() {
          @Override
          public void sayHello(
              Helloworld.Request req, StreamObserver<Helloworld.Response> responseObserver) {
            Helloworld.Response reply =
                Helloworld.Response.newBuilder().setMessage("Hello " + req.getName()).build();
            responseObserver.onNext(reply);
            responseObserver.onCompleted();
          }
        };

    Server server = configureServer(ServerBuilder.forPort(0).addService(greeter)).build().start();

    ManagedChannel channel = createChannel(server);

    Metadata extraMetadata = new Metadata();
    extraMetadata.put(
        Metadata.Key.of(SERVER_REQUEST_METADATA_KEY, Metadata.ASCII_STRING_MARSHALLER),
        serverMetadataValue);
    extraMetadata.put(
        Metadata.Key.of(CLIENT_REQUEST_METADATA_KEY, Metadata.ASCII_STRING_MARSHALLER),
        clientMetadataValue);

    GreeterGrpc.GreeterBlockingStub client =
        GreeterGrpc.newBlockingStub(channel)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(extraMetadata));

    Helloworld.Response response =
        testing()
            .runWithSpan(
                "parent",
                () -> client.sayHello(Helloworld.Request.newBuilder().setName("test").build()));

    OpenTelemetryAssertions.assertThat(response.getMessage()).isEqualTo("Hello test");

    testing()
        .waitAndAssertTraces(
            trace ->
                trace.hasSpansSatisfyingExactly(
                    span -> span.hasName("parent").hasKind(SpanKind.INTERNAL).hasNoParent(),
                    span ->
                        span.hasName("example.Greeter/SayHello")
                            .hasKind(SpanKind.CLIENT)
                            .hasParent(trace.getSpan(0))
                            .hasAttribute(
                                clientAttributeKey, Collections.singletonList(clientMetadataValue)),
                    span ->
                        span.hasName("example.Greeter/SayHello")
                            .hasKind(SpanKind.SERVER)
                            .hasParent(trace.getSpan(1))
                            .hasAttribute(
                                serverAttributeKey,
                                Collections.singletonList(serverMetadataValue))));
  }

  private ManagedChannel createChannel(Server server) throws Exception {
    ManagedChannelBuilder<?> channelBuilder =
        configureClient(ManagedChannelBuilder.forAddress("localhost", server.getPort()));
    return createChannel(channelBuilder);
  }

  static ManagedChannel createChannel(ManagedChannelBuilder<?> channelBuilder) throws Exception {
    usePlainText(channelBuilder);
    return channelBuilder.build();
  }

  private static void usePlainText(ManagedChannelBuilder<?> channelBuilder) throws Exception {
    // Depending on the version of gRPC usePlainText may or may not take an argument.
    try {
      channelBuilder
          .getClass()
          .getMethod("usePlaintext", boolean.class)
          .invoke(channelBuilder, true);
    } catch (NoSuchMethodException unused) {
      channelBuilder.getClass().getMethod("usePlaintext").invoke(channelBuilder);
    }
  }

  static List<AttributeAssertion> addExtraClientAttributes(AttributeAssertion... assertions) {
    List<AttributeAssertion> result = new ArrayList<>();
    result.addAll(Arrays.asList(assertions));
    if (Boolean.getBoolean("testLatestDeps")) {
      result.add(equalTo(SemanticAttributes.NET_SOCK_PEER_ADDR, "127.0.0.1"));
    }
    return result;
  }
}
