package com.mattlangsenkamp.oteldemo.postgresconsumer

import cats.effect.*, cats.effect.implicits.*, cats.effect.syntax.*
import cats.*, cats.implicits.*, cats.syntax.*

import fly4s.Fly4s
import fly4s.data.{
  BaselineResult,
  Fly4sConfig,
  MigrateResult,
  ValidatedMigrateResult,
  Location as MigrationLocation
}
import skunk.implicits.*
import skunk.codec.all.*
import natchez.Trace.Implicits.*
import skunk.*
import com.comcast.ip4s.Literals.port
import io.opentelemetry.api.GlobalOpenTelemetry

import fs2.kafka.*

import com.mattlangsenkamp.oteldemo.core.Core.*
import com.mattlangsenkamp.oteldemo.kafkatracing.KafkaTracing.{given, *}

import org.typelevel.otel4s.Otel4s
import org.typelevel.otel4s.java.OtelJava
import org.typelevel.otel4s.trace.Tracer

object Main extends IOApp.Simple:
  val consumerSettings =
    ConsumerSettings[IO, String, String]
      .withAutoOffsetReset(AutoOffsetReset.Earliest)
      .withBootstrapServers("kafka1:9092")
      .withGroupId("postgres_consumer")

  def runFlywayMigration: IO[MigrateResult] =
    Fly4s
      .make[IO](
        url = "jdbc:postgresql://postgres:5432/oteldemo",
        user = Some("oteldemo"),
        password = Some("password".toCharArray()),
        config = Fly4sConfig(
          table = "flyway",
          locations = List(MigrationLocation("db"))
        )
      )
      .use: fw =>
        fw.baseline *> fw.migrate

  def skunkSessionPool: Resource[IO, Resource[IO, Session[IO]]] =
    Session.pooled[IO](
      host = "postgres",
      port = 5432,
      user = "oteldemo",
      database = "oteldemo",
      password = Some("password"),
      max = 4
    )

  val insertMessage: Command[String] =
    sql"""
        INSERT INTO postgres_messages (message)
        VALUES ($varchar);
        """.command

  def run =
    otelResource[IO]
      .use: otel4s =>
        otel4s.tracerProvider
          .get("inference-service")
          .flatMap: trace =>
            given Tracer[IO] = trace
            runFlywayMigration *>
              skunkSessionPool.use: sessionPool =>
                KafkaConsumer
                  .stream(consumerSettings)
                  .subscribeTo("preprocessed_messages")
                  .records
                  .mapAsync(25) { committable =>
                    fromTracingCarrier(
                      committable.record.headers,
                      "postgres consumer"
                    ): s =>
                      val (key, value) = processRecord(committable.record)
                      randomSleep[IO](500, 2500).flatMap: message =>
                        val newVal = s"$value postgres consumer: $message"
                        trace
                          .spanBuilder("persist to postgres")
                          .withParent(s.context)
                          .build
                          .surround(sessionPool.use: session =>
                            session
                              .prepare(insertMessage)
                              .flatMap(_.execute(newVal)))
                  }
                  .compile
                  .drain
