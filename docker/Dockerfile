# 多阶段构建：构建期用完整 Maven+JDK 镜像，运行期只带 JRE，镜像小、攻击面小
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
# 先只拷 pom 拉依赖：代码改动不会让依赖层缓存失效，重复构建从 5min 降到 30s
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /build/target/*.jar app.jar
EXPOSE 8080
# -Xmx512m 对应 2G 服务器预算（见 docker-compose.yml 注释）；
# MaxRAMPercentage 与 Xmx 二选一，这里显式 Xmx 更直观
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS:--Xmx512m} -jar app.jar"]
