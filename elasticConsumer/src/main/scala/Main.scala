package com.mattlangsenkamp.oteldemo.elasticconsumer

import cats.effect.*, cats.effect.syntax.*, cats.effect.implicits.*
import com.sksamuel.elastic4s.cats.effect.instances.*
import com.sksamuel.elastic4s.fields.TextField
import com.sksamuel.elastic4s.http.JavaClient
import com.sksamuel.elastic4s.requests.common.RefreshPolicy
import com.sksamuel.elastic4s.requests.searches.SearchResponse
import com.sksamuel.elastic4s.ElasticClient
import com.sksamuel.elastic4s.ElasticProperties
import com.sksamuel.elastic4s.ElasticDsl.*
import fs2.kafka.*

object Main extends IOApp.Simple:

  val consumerSettings =
    ConsumerSettings[IO, String, String]
      .withAutoOffsetReset(AutoOffsetReset.Earliest)
      .withBootstrapServers("kafka1:9092")
      .withGroupId("elastic_consumer")

  def processRecord(
      record: ConsumerRecord[String, String]
  ): IO[(String, String)] =
    IO.pure(record.key -> record.value)

  def mkClient(uri: String): Resource[IO, ElasticClient] = Resource.make {
    IO.delay(ElasticClient(JavaClient(ElasticProperties(uri))))
  } { esClient => IO.delay(esClient.close()) }

  def run =
    mkClient("http://elasticsearch:9200").use { client =>
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
            committable.record.headers
            IO.println(committable.record) *>
              processRecord(committable.record).flatMap { case (key, value) =>
                client.execute {
                  indexInto("elastic_messages")
                    .fields(key -> value)
                    .refresh(RefreshPolicy.Immediate)
                }
              }
          }
          .compile
          .drain
    }
