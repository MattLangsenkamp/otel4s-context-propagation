package com.mattlangsenkamp.oteldemo.kafkatracing
import org.typelevel.otel4s.context.propagation.{TextMapGetter, TextMapUpdater}
import fs2.kafka.Headers
import fs2.kafka.Deserializer.string
import fs2.kafka.HeaderSerializer.string
import com.mattlangsenkamp.oteldemo.core.Core.withTracingCarrier
import cats.kernel.Monoid
import fs2.kafka.Header
import cats.*, cats.syntax.*, cats.implicits.*
import fs2.kafka.ConsumerRecord

object KafkaTracing:

  def processRecord(
      record: ConsumerRecord[String, String]
  ): (String, String) =
    (record.key -> record.value)

  given headersMonoid: Monoid[Headers] with
    def combine(x: Headers, y: Headers): Headers = x.concat(y)
    def empty: Headers = Headers()

  given getter: TextMapGetter[Headers] with
    def get(carrier: Headers, key: String): Option[String] =
      carrier.apply(key = key).map(_.as[String])
    def keys(carrier: Headers): Iterable[String] =
      carrier.toChain.toList.map(_.key)

  given updater: TextMapUpdater[Headers] with
    def updated(carrier: Headers, key: String, value: String): Headers =
      carrier.append(Header(key, value))
