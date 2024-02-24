val scala3Version = "3.3.1"
val http4sVersion = "0.23.25"
val http4sMiddlewareVersion = "0.3.0"
val otel4sVersion = "0.4.0"
val fs2KafkaVersion = "3.2.0"
val elastic4sVersion = "8.11.5"
val skunkVersion = "0.6.0"
val fly4sVersion = "1.0.1"
val postgresVersion = "42.7.0"
val flywayPostgresVersion = "10.7.2"
val cassandraDriverVersion = "4.18.0"
val openTelemetryVersion = "1.34.0"
val log4catsVersion = "2.6.0"
val logbackClassicVersion = "1.2.11"

val javaOpts = "JAVA_OPTS" ->
  "-Dotel.java.global-autoconfigure.enabled=true -Dotel.service.name=otel-demo -Dotel.exporter.otlp.endpoint=http://otel-collector:4317"

val dockerImageName =
  "ghcr.io/graalvm/jdk-community:21" // "eclipse-temurin:21-jre"

ThisBuild / scalaVersion := scala3Version

val observabilityDependencies = Seq(
  "ch.qos.logback" % "logback-classic" % logbackClassicVersion % Runtime,
  "io.opentelemetry" % "opentelemetry-exporter-otlp" % openTelemetryVersion % Runtime,
  "io.opentelemetry" % "opentelemetry-sdk-extension-autoconfigure" % openTelemetryVersion % Runtime
)

lazy val http = project
  .in(file("http"))
  .settings(
    version := "0.1.0-SNAPSHOT",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-client" % http4sVersion,
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion,
      "io.grpc" % "grpc-netty-shaded" % scalapb.compiler.Version.grpcJavaVersion,
      "org.http4s" %% "http4s-otel4s-middleware" % http4sMiddlewareVersion
    ),
    libraryDependencies ++= observabilityDependencies,
    fork := true,
    docker / dockerfile := {
      val appDir: File = stage.value
      val targetDir = "/app"

      new Dockerfile {
        from(dockerImageName)
        expose(8080)
        env(javaOpts)
        entryPoint(s"$targetDir/bin/${executableScriptName.value}")
        copy(appDir, targetDir, chown = "daemon:daemon")
      }
    },
    docker / imageNames := Seq(ImageName("mattlangsenkamp/oteldemo-http"))
  )
  .enablePlugins(sbtdocker.DockerPlugin, JavaAppPackaging)
  .dependsOn(grpc)

lazy val grpcServer = project
  .in(file("grpcServer"))
  .settings(
    version := "0.1.0-SNAPSHOT",
    libraryDependencies ++= Seq(
      "io.grpc" % "grpc-netty-shaded" % scalapb.compiler.Version.grpcJavaVersion,
      "com.github.fd4s" %% "fs2-kafka" % fs2KafkaVersion
    ),
    libraryDependencies ++= observabilityDependencies,
    fork := true,
    docker / dockerfile := {
      val appDir: File = stage.value
      val targetDir = "/app"

      new Dockerfile {
        from(dockerImageName)
        expose(9999)
        env(javaOpts)
        entryPoint(s"$targetDir/bin/${executableScriptName.value}")
        copy(appDir, targetDir, chown = "daemon:daemon")
      }
    },
    docker / imageNames := Seq(ImageName("mattlangsenkamp/oteldemo-grpc"))
  )
  .enablePlugins(sbtdocker.DockerPlugin, JavaAppPackaging)
  .dependsOn(grpc, kafka)

lazy val elasticConsumer = project
  .in(file("elasticConsumer"))
  .settings(
    version := "0.1.0-SNAPSHOT",
    libraryDependencies ++= Seq(
      "com.sksamuel.elastic4s" %% "elastic4s-client-esjava" % elastic4sVersion,
      "com.sksamuel.elastic4s" % "elastic4s-effect-cats_3" % elastic4sVersion
    ),
    libraryDependencies ++= observabilityDependencies,
    fork := true,
    docker / dockerfile := {
      val appDir: File = stage.value
      val targetDir = "/app"

      new Dockerfile {
        from(dockerImageName)
        expose(9200)
        env(javaOpts)
        entryPoint(s"$targetDir/bin/${executableScriptName.value}")
        copy(appDir, targetDir, chown = "daemon:daemon")
      }
    },
    docker / imageNames := Seq(
      ImageName("mattlangsenkamp/oteldemo-elastic-consumer")
    )
  )
  .dependsOn(kafka)
  .enablePlugins(sbtdocker.DockerPlugin, JavaAppPackaging)

lazy val postgresConsumer = project
  .in(file("postgresConsumer"))
  .settings(
    version := "0.1.0-SNAPSHOT",
    libraryDependencies ++= Seq(
      "org.tpolecat" %% "skunk-core" % skunkVersion,
      "com.github.geirolz" %% "fly4s" % fly4sVersion,
      "org.postgresql" % "postgresql" % postgresVersion,
      "org.flywaydb" % "flyway-database-postgresql" % flywayPostgresVersion % "runtime"
    ),
    libraryDependencies ++= observabilityDependencies,
    fork := true,
    docker / dockerfile := {
      val appDir: File = stage.value
      val targetDir = "/app"

      new Dockerfile {
        from(dockerImageName)
        env(javaOpts)
        entryPoint(s"$targetDir/bin/${executableScriptName.value}")
        copy(appDir, targetDir, chown = "daemon:daemon")
      }
    },
    docker / imageNames := Seq(
      ImageName("mattlangsenkamp/oteldemo-postgres-consumer")
    )
  )
  .dependsOn(kafka)
  .enablePlugins(sbtdocker.DockerPlugin, JavaAppPackaging)

lazy val cassandraConsumer = project
  .in(file("cassandraConsumer"))
  .settings(
    version := "0.1.0-SNAPSHOT",
    libraryDependencies ++= Seq(
      "org.apache.cassandra" % "java-driver-core" % cassandraDriverVersion
    ),
    libraryDependencies ++= observabilityDependencies,
    fork := true,
    docker / dockerfile := {
      val appDir: File = stage.value
      val targetDir = "/app"

      new Dockerfile {
        from(dockerImageName)
        expose(9042)
        env(javaOpts)
        entryPoint(s"$targetDir/bin/${executableScriptName.value}")
        copy(appDir, targetDir, chown = "daemon:daemon")
      }
    },
    docker / imageNames := Seq(
      ImageName("mattlangsenkamp/oteldemo-cassandra-consumer")
    )
  )
  .dependsOn(kafka)
  .enablePlugins(sbtdocker.DockerPlugin, JavaAppPackaging)

lazy val grpc =
  project
    .in(file("grpc"))
    .settings(
      version := "0.1.0-SNAPSHOT"
    )
    .dependsOn(core)
    .enablePlugins(Fs2Grpc)

lazy val kafka =
  project
    .in(file("kafka"))
    .settings(
      version := "0.1.0-SNAPSHOT",
      libraryDependencies += "com.github.fd4s" %% "fs2-kafka" % fs2KafkaVersion
    )
    .dependsOn(core)

lazy val core = project
  .in(file("core"))
  .settings(
    version := "0.1.0-SNAPSHOT",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "log4cats-slf4j" % log4catsVersion,
      "org.typelevel" %% "cats-effect" % "3.5.3",
      "org.typelevel" %% "otel4s-core" % otel4sVersion,
      "org.typelevel" %% "otel4s-java" % otel4sVersion
    )
  )
