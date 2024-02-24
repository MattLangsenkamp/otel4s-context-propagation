package com.mattlangsenkamp.oteldemo.grpcServer

import cats.effect.*, cats.effect.implicits.*, cats.effect.syntax.*
import scala.concurrent.duration.*
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import fs2.grpc.syntax.all._
import io.grpc.ServerServiceDefinition
import com.mattlangsenkamp.oteldemo.*
import com.mattlangsenkamp.oteldemo.BrokerPreprocessorGrpc.BrokerPreprocessor
import io.grpc.Metadata
import cats.effect.std.Random
import fs2.kafka.*
import fs2.kafka.KafkaAdminClient
import fs2.concurrent.Topic
import fs2.kafka.KafkaProducer.PartitionsFor
import org.apache.kafka.clients.admin.NewTopic
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.Otel4s
import io.opentelemetry.api.GlobalOpenTelemetry
import org.typelevel.otel4s.java.OtelJava
import collection.convert.ImplicitConversions.*
import io.grpc.Metadata.AsciiMarshaller
import io.grpc.InternalMetadata.TrustedAsciiMarshaller
import org.typelevel.otel4s.trace.SpanKind
import org.typelevel.otel4s.trace.Span
import org.typelevel.otel4s.Attribute
import cats.effect.kernel.Resource
import com.mattlangsenkamp.oteldemo.core.Core.{given, *}
import com.mattlangsenkamp.oteldemo.grpctracing.GrpcTracing.given
import com.mattlangsenkamp.oteldemo.kafkatracing.KafkaTracing.given

object Main extends IOApp.Simple:

  private val topic_name = "preprocessed_messages"

  def kafkaAdminClientResource(
      bootstrapServers: String
  ): Resource[IO, KafkaAdminClient[IO]] =
    KafkaAdminClient.resource[IO](AdminClientSettings(bootstrapServers))

  def brokerPreprocessor(
      producer: PartitionsFor[IO, String, String]
  )(using tracer: Tracer[IO]) =
    new BrokerPreprocessorFs2Grpc[IO, Metadata]:
      override def processAndPushToBroker(
          request: BrokerRequest,
          ctx: Metadata
      ): IO[BrokerResponse] =
        fromTracingCarrier(ctx, "grpc server"): s =>
          val kafkaSpan =
            tracer.spanBuilder("kafkaProduce").withParent(s.context).build
          for
            message <- randomSleep[IO](500, 2500)
            _ <-
              withTracingCarrier[IO, Headers, ProducerResult[String, String]](
                "push to broker"
              ): carrier =>
                producer
                  .produceOne(
                    ProducerRecord(
                      topic_name,
                      "message",
                      s"gRPC preprocessor: $message\t"
                    )
                      .withHeaders(carrier)
                  )
                  .flatten
          yield (BrokerResponse(message = message))

  def brokerPreprocessorService(
      producer: PartitionsFor[IO, String, String]
  )(using t: Tracer[IO]): Resource[IO, ServerServiceDefinition] =
    BrokerPreprocessorFs2Grpc.bindServiceResource[IO](
      brokerPreprocessor(producer)
    )

  val producerSettings =
    ProducerSettings[IO, String, String].withBootstrapServers("kafka1:9092")

  def run(service: ServerServiceDefinition) = NettyServerBuilder
    .forPort(9999)
    .addService(service)
    .resource[IO]
    .evalMap(server =>
      IO(server.start()) *> IO.println("Started gRPC server on port 9999")
    )
    .useForever

  def run =
    otelResource[IO]
      .use: otel4s =>
        otel4s.tracerProvider
          .get("otel-demo")
          .flatMap: trace =>
            given Tracer[IO] = trace
            kafkaAdminClientResource("kafka1:9092")
              .use { client =>
                for
                  topics <- client.listTopics.names
                  topic_exists = topics.contains(topic_name)
                  _ <-
                    if !topic_exists then
                      client.createTopic(NewTopic(topic_name, 1, 1.toShort))
                    else IO.unit
                yield ()
              }
              .flatMap: yea =>
                KafkaProducer
                  .stream(producerSettings)
                  .flatMap { producer =>
                    fs2.Stream.eval(
                      brokerPreprocessorService(producer).use(run)
                    )
                  }
                  .compile
                  .drain
