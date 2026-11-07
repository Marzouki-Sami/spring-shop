# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q -DskipTests package \
    && java -Djarmode=tools -jar target/shop-*.jar extract --layers --destination /workspace/layers

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --no-create-home shop
WORKDIR /app
COPY --from=build /workspace/layers/dependencies/ ./
COPY --from=build /workspace/layers/spring-boot-loader/ ./
COPY --from=build /workspace/layers/snapshot-dependencies/ ./
COPY --from=build /workspace/layers/application/ ./
RUN mkdir -p /data/images && chown shop /data/images
VOLUME /data/images
USER shop
EXPOSE 8081
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "shop-0.1.0-SNAPSHOT.jar"]