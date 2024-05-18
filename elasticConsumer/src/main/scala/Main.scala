package com.mattlangsenkamp.oteldemo.elasticconsumer

import cats.effect.*, cats.effect.syntax.*, cats.effect.implicits.*
import com.sksamuel.elastic4s.cats.effect.instances.*
import com.sksamuel.elastic4s.fields.TextField
import com.sksamuel.elastic4s.http.JavaClient
import com.sksamuel.elastic4s.requests.common.RefreshPolicy
import com.sksamuel.elastic4s.requests.searches.SearchResponse
import com.sksamuel.elastic4s.{ElasticClient, ElasticProperties}
import com.sksamuel.elastic4s.ElasticDsl.*
import fs2.kafka.*
import com.mattlangsenkamp.oteldemo.core.Core.fromTracingCarrier
import com.mattlangsenkamp.oteldemo.kafkatracing.KafkaTracing.{
  processRecord,
  given
}
import org.typelevel.otel4s.Otel4s
import io.opentelemetry.api.GlobalOpenTelemetry
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.trace.Tracer
import com.mattlangsenkamp.oteldemo.core.Core.*

object Main extends IOApp.Simple:

  val consumerSettings =
    ConsumerSettings[IO, String, String]
      .withAutoOffsetReset(AutoOffsetReset.Earliest)
      .withBootstrapServers("kafka1:9092")
      .withGroupId("elastic_consumer")

  def mkClient(uri: String): Resource[IO, ElasticClient] = Resource.make {
    IO.delay(ElasticClient(JavaClient(ElasticProperties(uri))))
  } { esClient => IO.delay(esClient.close()) }

  def run =
    OtelJava
      .autoConfigured[IO]()
      .use: otel4s =>
        otel4s.tracerProvider
          .get("otel-demo")
          .flatMap: trace =>
            given Tracer[IO] = trace
            mkClient("http://elasticsearch:9200").use: client =>
              client.execute {
                createIndex("elastic_messages").mapping(
                  properties(TextField("message").index(true))
                )
              } *>
                KafkaConsumer
                  .stream(consumerSettings)
                  .subscribeTo("preprocessed_messages")
                  .records
                  .mapAsync(25) { committable =>
                    fromTracingCarrier(
                      committable.record.headers,
                      "elastic consumer"
                    ): s =>
                      val (key, value) = processRecord(committable.record)
                      randomSleep[IO](500, 2500).flatMap: message =>
                        trace
                          .spanBuilder("persist to elastic search")
                          .withParent(s.context)
                          .build
                          .surround(
                            client.execute:
                              indexInto("elastic_messages")
                                .fields(
                                  key -> s"$value elastic consumer: $message"
                                )
                                .refresh(RefreshPolicy.Immediate)
                          )
                  }
                  .compile
                  .drain
