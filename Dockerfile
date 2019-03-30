FROM openjdk:8u171-jdk-apline3.8 AS builder

ADD . /app
WORKDIR /app

RUN ./gradlew shadowJar && mv build/libs/*.jar /server.jar

FROM openjdk:8u171-jre-apline3.8 AS env

WORKDIR /app
COPY --from=builder /server.jar .

ENTRYPOINT java -jar /app/server.jar

