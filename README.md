# Context Propagation with otel4s

## System Requirements 

This application depends on scala, sbt and docker. It was developed with sbt 1.9.8, scala 3.3.1 and Docker 25.0.3. Other version will likely work for all dependencies.

## System Architecture
Below is a diagram of the whole system. The arrows from one service to another represent the direction the data flows.
![system architecture](/assets/oteldemo.svg)

## Running

Built the docker containers by running `sbt docker`

Run `docker compose up -d` to start all containers. 

After a second or two run the following command in the console. 

`curl -X POST localhost:8080/api/v1/push_message?message=hello!`

you should get a response telling you how long it waited in the gRPC server.

## Viewing the Datastores
### Elasticsearch
Go to `http://localhost:5601/app/dev_tools#/console`
Run the following query:
```
GET elastic_messages/_search
{
  "query": {
    "match_all": {}
  }
}
```
### Postgres
Log into postgres using something like DBeaver. Run the following query:
```
select * from postgres_messages;
```
### Cassandra
Start a CQL session using docker
```
docker run --rm -it --network oteldemo-network nuvo/docker-cqlsh cqlsh cassandra 9042 --cqlversion='3.4.6'
```
then run 
```
select * from store.cassandra_messages;
```

## Tracing
To see traces for each http request go to `http://localhost:16686/search`. Individual traces look like what is displayed below.
![jaeger trace](/assets/jaeger-trace.png)


