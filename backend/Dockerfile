# ---- 构建阶段 ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# 先只拷贝 pom，利用镜像层缓存预热依赖
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q -DskipTests package

# ---- 运行阶段 ----
FROM eclipse-temurin:17-jre
WORKDIR /app

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"

COPY --from=build /workspace/target/*.jar app.jar

RUN mkdir -p /data/uploads
VOLUME ["/data/uploads"]

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
