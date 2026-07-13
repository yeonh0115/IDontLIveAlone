# 1단계: Gradle 자바 21 환경에서 빌드 진행
FROM gradle:8.5-jdk21 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN ./gradlew clean build -x test --no-daemon

# 2단계: 자바 21 실행 환경 구축
FROM openjdk:21-ea-17-jdk-slim
EXPOSE 10000
COPY --from=build /home/gradle/src/build/libs/*-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "/app.jar"]
