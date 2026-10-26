# Build con el wrapper de Maven (misma version que en desarrollo) y runtime solo con JRE.
FROM eclipse-temurin:21.0.12_8-jdk-alpine-3.24 AS build
WORKDIR /src
# Primero solo el pom: las dependencias quedan en una capa cacheada mientras no cambie.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
COPY src/main/ src/main/
# Los tests corren fuera de la imagen (los *IT necesitan Docker); aqui ni se compilan.
RUN ./mvnw -q -B -Dmaven.test.skip=true package && cp target/order-processor-*.jar /app.jar

FROM eclipse-temurin:21.0.12_8-jre-alpine-3.24
RUN addgroup -S app && adduser -S app -G app
USER app
COPY --from=build /app.jar /app/app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
