package com.mattlangsenkamp.oteldemo.http

import cats.*, cats.implicits.*, cats.syntax.*
import cats.effect.*, cats.effect.implicits.*, cats.effect.syntax.*
import org.http4s.ember.server.EmberServerBuilder
import com.comcast.ip4s.{host, port}
import org.http4s.dsl.Http4sDsl
import org.http4s.*
import io.grpc.netty.shaded.io.grpc.netty.{NettyChannelBuilder}
import fs2.grpc.syntax.all._
import io.grpc.ManagedChannel
import com.mattlangsenkamp.oteldemo.BrokerPreprocessorFs2Grpc
import io.grpc.Metadata
import com.mattlangsenkamp.oteldemo.BrokerRequest
import org.http4s.server.middleware.RequestLogger
import org.http4s.server.middleware.ResponseLogger
import org.typelevel.otel4s.Otel4s
import org.typelevel.otel4s.java.OtelJava
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.context.syntax.*
import io.opentelemetry.api.GlobalOpenTelemetry
import org.http4s.otel4s.middleware.{ServerMiddleware => OtelServerMiddleware}
import io.opentelemetry.api.OpenTelemetry
import com.mattlangsenkamp.oteldemo.grpctracing.GrpcTracing.withTracingHeaders

object Main extends IOApp.Simple:

  val brokerPreprocessorResource =
    NettyChannelBuilder
      .forTarget("grpc:9999")
      .usePlaintext()
      .resource[IO]
      .flatMap(ch => BrokerPreprocessorFs2Grpc.stubResource[IO](ch))

  final class MyRoutes(
      brokerPreprocessor: BrokerPreprocessorFs2Grpc[IO, Metadata]
  )(using Tracer[IO])
      extends Http4sDsl[IO]:

    object Message extends QueryParamDecoderMatcher[String]("message")

    val routes = HttpRoutes.of[IO] {

      case POST -> Root / "api" / "v1" / "push_message" :? Message(message) =>
        withTracingHeaders { meta =>
          brokerPreprocessor
            .processAndPushToBroker(
              BrokerRequest(message = message),
              ctx = meta
            )
        }
          .flatMap(br => Ok(br.message))
    }

  private def otelResource: Resource[IO, Otel4s[IO]] =
    Resource
      .eval(IO.delay(GlobalOpenTelemetry.get))
      .evalMap(OtelJava.forAsync[IO])

  def run =
    otelResource
      .use { otel4s =>
        otel4s.tracerProvider.get("inference-service").flatMap { trace =>
          given Tracer[IO] = trace
          brokerPreprocessorResource.use { brokerPreprocessor =>
            val s = EmberServerBuilder
              .default[IO]
              .withPort(port"8080")
              .withHost(host"0.0.0.0")
              .withHttpApp(
                OtelServerMiddleware
                  .default[IO]
                  .buildHttpApp(
                    MyRoutes(brokerPreprocessor).routes.orNotFound
                  )
              )
              .build
            s.evalTap(srv =>
              IO.println(s"Server funning on address ${srv.address}")
            ).useForever
          }
        }
      }
