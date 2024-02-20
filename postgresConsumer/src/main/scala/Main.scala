package com.mattlangsenkamp.oteldemo.postgresconsumer

import cats.effect.IOApp
import fs2.kafka.*
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

object Main extends IOApp.Simple:
  val consumerSettings =
    ConsumerSettings[IO, String, String]
      .withAutoOffsetReset(AutoOffsetReset.Earliest)
      .withBootstrapServers("kafka1:9092")
      .withGroupId("postgres_consumer")

  def processRecord(
      record: ConsumerRecord[String, String]
  ): IO[(String, String)] =
    IO.pure(record.key -> record.value)

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
      .use { fw =>
        fw.baseline *> fw.migrate
      }

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
    runFlywayMigration *>
      skunkSessionPool.use { sessionPool =>
        KafkaConsumer
          .stream(consumerSettings)
          .subscribeTo("preprocessed_messages")
          .records
          .mapAsync(25) { committable =>
            IO.println(committable.record) *>
              processRecord(committable.record).flatMap { case (key, value) =>
                sessionPool.use { session =>
                  session.prepare(insertMessage).flatMap(_.execute(value))
                }
              }
          }
          .compile
          .drain
      }
