package com.mattlangsenkamp.oteldemo.core
import cats.*, cats.syntax.*, cats.implicits.*
import cats.effect.*, cats.effect.syntax.*, cats.effect.implicits.*
import org.typelevel.otel4s.trace.Span
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.context.propagation.TextMapUpdater
import org.typelevel.otel4s.context.propagation.TextMapGetter
import org.typelevel.otel4s.trace.SpanKind
import org.typelevel.otel4s.context.propagation.TextMapPropagator
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.Otel4s
import io.opentelemetry.api.GlobalOpenTelemetry
import org.typelevel.otel4s.oteljava.OtelJava
import cats.effect.std.Random
import scala.concurrent.duration.*

object Core:

  def randomSleep[F[_]: Async](min: Int, max: Int): F[String] =
    for
      rand <- Random.scalaUtilRandom[F]
      millisecondsToWait <- rand
        .betweenInt(min, max)
        .map(_.milliseconds)
      _ <- Async[F].sleep(millisecondsToWait)
      message = s"waited for ${millisecondsToWait}"
    yield message

  def fromTracingCarrier[
      F[_]: Tracer: Concurrent,
      C: Monoid: TextMapGetter,
      O
  ](carrier: C, spanName: String)(body: Span[F] => F[O]): F[O] =
    MonadCancelThrow[F].uncancelable: poll =>
      Tracer[F].joinOrRoot(carrier):
        Tracer[F]
          .spanBuilder(spanName)
          .withSpanKind(SpanKind.Server)
          .build
          .use: span =>
            poll(body(span)).guaranteeCase:
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

  def withTracingCarrier[
      F[_]: Tracer: Concurrent,
      C: TextMapUpdater: Monoid,
      O
  ](spanName: String)(
      body: C => F[O]
  ): F[O] =
    MonadCancelThrow[F].uncancelable: poll =>
      val carrier = Monoid[C].empty
      Tracer[F]
        .spanBuilder(spanName)
        .withSpanKind(SpanKind.Client)
        .build
        .use: span =>
          for
            traceHeaders <- Tracer[F].propagate(carrier)
            resp <- poll(body(traceHeaders)).guaranteeCase:
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
          yield resp
