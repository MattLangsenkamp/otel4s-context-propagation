package com.mattlangsenkamp.oteldemo

import cats.effect.*, cats.effect.implicits.*, cats.effect.syntax.*
import cats.*, cats.implicits.*, cats.syntax.*

import fs2.kafka.*
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.*;
import java.net.InetSocketAddress
import com.mattlangsenkamp.oteldemo.core.Core.*
import com.mattlangsenkamp.oteldemo.kafkatracing.KafkaTracing.{given, *}
import org.typelevel.otel4s.Otel4s
import io.opentelemetry.api.GlobalOpenTelemetry
import org.typelevel.otel4s.java.OtelJava
import org.typelevel.otel4s.trace.Tracer

object Main extends IOApp.Simple:

  val consumerSettings =
    ConsumerSettings[IO, String, String]
      .withAutoOffsetReset(AutoOffsetReset.Earliest)
      .withBootstrapServers("kafka1:9092")
      .withGroupId("cassandra_consumer")

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

  def run =
    otelResource[IO]
      .use: otel4s =>
        otel4s.tracerProvider
          .get("inference-service")
          .flatMap: trace =>
            given Tracer[IO] = trace
            mkClient().use: session =>
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
                    fromTracingCarrier(
                      committable.record.headers,
                      "cassandra consumer"
                    ): s =>
                      val (key, value) = processRecord(committable.record)
                      randomSleep[IO](500, 2500).flatMap: message =>
                        val newVal = s"$value cassandra consumer: $message"
                        trace
                          .spanBuilder("persist to cassandra")
                          .withParent(s.context)
                          .build
                          .surround(IO.blocking:
                            val sr = session.execute(
                              f"INSERT INTO store.cassandra_messages (id, message) VALUES (now(), '$newVal');"
                            )
                          )
                  }
                  .compile
                  .drain
