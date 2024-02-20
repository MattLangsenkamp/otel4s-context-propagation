package com.mattlangsenkamp.oteldemo

import cats.effect.*, cats.effect.implicits.*, cats.effect.syntax.*
import cats.*, cats.implicits.*, cats.syntax.*

import fs2.kafka.*
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.*;
import java.net.InetSocketAddress

object Main extends IOApp.Simple:

  val consumerSettings =
    ConsumerSettings[IO, String, String]
      .withAutoOffsetReset(AutoOffsetReset.Earliest)
      .withBootstrapServers("kafka1:9092")
      .withGroupId("cassandra_consumer")

  def processRecord(
      record: ConsumerRecord[String, String]
  ): IO[(String, String)] =
    IO.pure(record.key -> record.value)

  def mkClient() =
    Resource.make(
      IO.delay(
        CqlSession
          .builder()
          .addContactPoint(new InetSocketAddress("cassandra", 9042))
          .withLocalDatacenter("datacenter1")
          .build()
      )
    )(s => IO.delay(s.close()))
  def run = mkClient().use { session =>
    session
      .execute(
        """CREATE KEYSPACE IF NOT EXISTS store 
        |WITH REPLICATION = 
        |{ 'class' : 'SimpleStrategy', 'replication_factor' : '1' };""".stripMargin
      )
      .pure[IO] *>
      session
        .execute(
          "CREATE TABLE IF NOT EXISTS store.cassandra_messages ( id uuid PRIMARY KEY, message VARCHAR );"
        )
        .pure[IO]
      *> KafkaConsumer
        .stream(consumerSettings)
        .subscribeTo("preprocessed_messages")
        .records
        .mapAsync(25) { committable =>
          IO.println(committable.record) *>
            processRecord(committable.record).flatMap { case (key, value) =>
              IO.blocking {
                val sr = session.execute(
                  f"INSERT INTO store.cassandra_messages (id, message) VALUES (now(), '$value');"
                )
                println(sr)
              }
            }
        }
        .compile
        .drain
  }
