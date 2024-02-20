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
import com.mattlangsenkamp.oteldemo.grpctracing.GrpcTracing.fromTracingHeaders

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
        ctx.fromTracingHeaders { s =>
          val kafkaSpan =
            tracer.spanBuilder("kafkaProduce").withParent(s.context).build
          for
            rand <- Random.scalaUtilRandom[IO]
            millisecondsToWait <- rand
              .betweenInt(500, 2500)
              .map(_.milliseconds)
            _ <- IO.sleep(millisecondsToWait)
            message = s"waited for ${millisecondsToWait} milliseconds"
            wow <- kafkaSpan.surround {
              val pee = ProducerRecord(topic_name, "message", message)
              pee.headers
              producer
                .produceOne(
                  ProducerRecord(topic_name, "message", message)
                )
                .flatten
            }
          yield (BrokerResponse(message = message))
        }

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

  private def otelResource: Resource[IO, Otel4s[IO]] =
    Resource
      .eval(IO.delay(GlobalOpenTelemetry.get))
      .evalMap(OtelJava.forAsync[IO])

  def run =
    otelResource
      .use { otel4s =>
        otel4s.tracerProvider.get("inference-service").flatMap { trace =>
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
            .flatMap { yea =>
              KafkaProducer
                .stream(producerSettings)
                .flatMap { producer =>
                  fs2.Stream.eval(brokerPreprocessorService(producer).use(run))
                }
                .compile
                .drain
            }
        }
      }
