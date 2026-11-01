# Built with the repo root as context: the product build at src/ and the application build at
# apps/server/, at their repository-relative layout
FROM eclipse-temurin:21.0.13_9-jdk@sha256:ebfbb18d11ff163ee83b21258acf12248af48d22a0fdfdc68fa1a1516c05976a AS build
WORKDIR /repo/apps/server
COPY src/ /repo/src/
COPY apps/server/ /repo/apps/server/
RUN ./gradlew --no-daemon :faye-app:bootJar

# Alpine JRE: a third the size of the Ubuntu one; sqlite-jdbc ships a musl build of its native library
FROM eclipse-temurin:21.0.13_9-jre-alpine@sha256:82cf3d9ccbe1ed46252008d51b2f2329e6b9e8a2f4394c4bebefc3ea7f4b1372
COPY --from=build /repo/apps/server/faye-app/build/libs/faye-app-0.1.0-SNAPSHOT.jar /app/faye.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/faye.jar"]
