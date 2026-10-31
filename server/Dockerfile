FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon :faye-app:bootJar

FROM eclipse-temurin:21-jre
COPY --from=build /src/faye-app/build/libs/faye-app-0.1.0-SNAPSHOT.jar /app/faye.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/faye.jar"]
