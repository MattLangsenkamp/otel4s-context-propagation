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

object Core:

  def fromTracingHeaders[
      F[_]: Tracer: Concurrent,
      H: Monoid: TextMapGetter,
      O
  ](meta: H, spanName: String)(body: Span[F] => F[O]): F[O] =
    MonadCancelThrow[F].uncancelable { poll =>
      Tracer[F].joinOrRoot(meta) {
        Tracer[F]
          .spanBuilder(spanName)
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

  def withTracingHeaders[
      F[_]: Tracer: Concurrent,
      H: TextMapUpdater: Monoid,
      O
  ](spanName: String)(
      body: H => F[O]
  ): F[O] =
    MonadCancelThrow[F].uncancelable { poll =>
      val meta = Monoid[H].empty
      Tracer[F]
        .spanBuilder(spanName)
        .withSpanKind(SpanKind.Client)
        .build
        .use { span =>
          for
            traceHeaders <- Tracer[F].propagate(meta)
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
