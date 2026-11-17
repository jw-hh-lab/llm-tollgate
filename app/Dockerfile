# 게이트웨이와 가짜 LLM 서버를 같은 Dockerfile로 만든다.
#   docker build -t llm-tollgate-gateway .
#   docker build --build-arg MODULE=fake-llm -t llm-tollgate-fake-llm .
# eclipse-temurin 이미지는 ARM(t4g)과 x86 모두 지원한다. ARM용 빌드는 docker buildx --platform linux/arm64

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# 의존성 레이어를 먼저 받아 두면 코드만 바뀌었을 때 빌드가 빨라진다
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY gateway/build.gradle gateway/
COPY fake-llm/build.gradle fake-llm/
RUN chmod +x gradlew && ./gradlew --no-daemon -q help
COPY gateway/src gateway/src
COPY fake-llm/src fake-llm/src
ARG MODULE=gateway
RUN ./gradlew --no-daemon -q :${MODULE}:bootJar && cp ${MODULE}/build/libs/*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --uid 10001 app
WORKDIR /app
COPY --from=build /app.jar app.jar
USER 10001
EXPOSE 8080 8081 9090
# 컨테이너 메모리 한도의 75%까지 힙으로 쓴다 (k8s resources.limits.memory 기준)
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
