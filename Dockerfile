

# Etapa 1: compilar con el JDK completo
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Primero solo los archivos de Gradle, para aprovechar la caché de Docker:
# si no cambian, las dependencias no se vuelven a descargar.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
# Corrige los saltos de línea de Windows y da permiso de ejecución
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# Ahora el código y la compilación del JAR ejecutable
COPY src ./src
RUN ./gradlew --no-daemon buildFatJar

# Etapa 2: imagen final ligera, solo con lo necesario para ejecutar
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --no-create-home nearhouse
COPY --from=build /app/build/libs/nearhouse-api-all.jar app.jar
USER nearhouse
EXPOSE 8080
CMD ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]