# 第一阶段：依赖下载
FROM openjdk:21-jdk-slim AS dependencies

WORKDIR /app

# 安装Maven
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

# 下载依赖
COPY pom.xml .
RUN mvn dependency:go-offline -B

# 第二阶段：构建阶段
FROM dependencies AS builder

# 构建应用
COPY src ./src
RUN mvn clean package -DskipTests -o

# 第三阶段：运行阶段
FROM openjdk:21-jre-slim

WORKDIR /app

# 创建非root用户
RUN groupadd -r appgroup && useradd -r -g appgroup appuser

# 安装curl用于健康检查
RUN apt-get update && \
    apt-get install -y curl && \
    rm -rf /var/lib/apt/lists/*

# 从构建阶段复制jar文件
COPY --from=builder /app/target/*.jar app.jar

# 设置文件权限
RUN chown appuser:appgroup app.jar

# 切换到非root用户
USER appuser

# 环境变量
ENV JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseG1GC -XX:+UseStringDeduplication -XX:MaxRAMPercentage=75.0"
ENV SPRING_PROFILES_ACTIVE=prod

# 暴露端口
EXPOSE 8080

# 健康检查
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# 启动应用
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]