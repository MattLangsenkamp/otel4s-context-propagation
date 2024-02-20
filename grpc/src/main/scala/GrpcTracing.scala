package com.mattlangsenkamp.oteldemo.grpctracing

import cats.effect.*, cats.effect.syntax.*, cats.effect.implicits.*
import io.grpc.Metadata
import org.typelevel.otel4s.trace.*
import org.typelevel.otel4s.Attribute
import collection.convert.ImplicitConversions.*
import java.lang
import org.typelevel.otel4s.context.propagation.TextMapGetter

object GrpcTracing:

  given getter: TextMapGetter[Metadata] with
    def get(carrier: Metadata, key: String): Option[String] =
      Option.apply(
        carrier.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER))
      )
    def keys(carrier: Metadata): Iterable[String] = carrier.keys().toSeq

  def withTracingHeaders[A](body: Metadata => IO[A])(using
      tracer: Tracer[IO]
  ): IO[A] =
    IO.uncancelable { poll =>
      val marsh = Metadata.ASCII_STRING_MARSHALLER
      val meta = Metadata()
      tracer
        .spanBuilder("grpc request")
        .withSpanKind(SpanKind.Client)
        .build
        .use { span =>
          for
            traceHeaders <-
              tracer.propagate(Map.empty[String, String])
            _ <-
              IO.pure(
                traceHeaders
                  .foreach((k, v) => meta.put(Metadata.Key.of(k, marsh), v))
              )
            resp <- poll(body(meta)).guaranteeCase {
              case Outcome.Succeeded(fa) =>
                span.addAttribute(Attribute("exit.case", "succeeded"))
              case Outcome.Errored(e) =>
                span.recordException(e) >>
                  span.addAttribute(Attribute("exit.case", "errored"))
              case Outcome.Canceled() =>
                span.addAttributes(
                  Attribute("exit.case", "canceled"),
                  Attribute("canceled", true)
                )
            }
          yield resp
        }
    }

  extension (meta: Metadata)
    def fromTracingHeaders[A](body: Span[IO] => IO[A])(using
        tracer: Tracer[IO]
    ): IO[A] =

      val marsh = Metadata.ASCII_STRING_MARSHALLER
      val metaMap =
        meta
          .keys()
          .iterator()
          .toList
          .foldLeft(Map.empty[String, String])((map, key) =>
            map + (key -> meta.get[String](Metadata.Key.of(key, marsh)))
          )
      IO.uncancelable { poll =>
        IO.println(metaMap) *>
          tracer.joinOrRoot(metaMap) {
            tracer
              .spanBuilder("grpc server")
              .withSpanKind(SpanKind.Server)
              .build
              .use { span =>
                poll(body(span)).guaranteeCase {
                  case Outcome.Succeeded(fa) =>
                    span.addAttribute(Attribute("exit.case", "succeeded"))
                  case Outcome.Errored(e) =>
                    span.recordException(e) >>
                      span.addAttribute(Attribute("exit.case", "errored"))
                  case Outcome.Canceled() =>
                    span.addAttributes(
                      Attribute("exit.case", "canceled"),
                      Attribute("canceled", true),
                      Attribute("error", true)
                    )
                }
              }
          }
      }
