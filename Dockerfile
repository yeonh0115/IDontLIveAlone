# 1단계: Gradle 자바 21 환경에서 빌드 진행
FROM gradle:8.5-jdk21 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src

# 💡 이 부분을 추가했습니다! gradlew 파일에 실행 권한을 강제로 줍니다.
RUN chmod +x gradlew

RUN ./gradlew clean build --no-daemon

# 2단계: 자바 21 실행 환경 구축
FROM eclipse-temurin:21-jre-jammy
EXPOSE 10000
COPY --from=build /home/gradle/src/build/libs/*-SNAPSHOT.jar app.jar
# Leave room for native libraries, thread stacks and the OS in a 512 MB instance.
ENTRYPOINT ["java", "-Xmx192m", "-XX:MaxMetaspaceSize=128m", "-XX:ReservedCodeCacheSize=64m", "-XX:+UseSerialGC", "-Xss512k", "-jar", "/app.jar"]
