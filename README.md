## sbt project compiled with Scala 3

### Usage

This is a normal sbt project. You can compile code with `sbt compile`, run it with `sbt run`, and `sbt console` will start a Scala 3 REPL.

For more information on the sbt-dotty plugin, see the
[scala3-example-project](https://github.com/scala/scala3-example-project/blob/main/README.md).

docker compose up -d

sbt "grpcServer/run"
sbt "http/run"
sbt "elasticConsumer/run"
sbt "postgresConsumer/run"
sbt "cassandraConsumer/run"

curl -X POST localhost:8080/api/v1/push_message?message=hello!

docker run --rm -it --network oteldemo-network nuvo/docker-cqlsh cqlsh cassandra 9042 --cqlversion='3.4.6'
select * from store.cassandra_messages;