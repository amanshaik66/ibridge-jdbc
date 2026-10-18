FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /app
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*
RUN curl -sSL -o jt400.jar https://repo1.maven.org/maven2/net/sf/jt400/jt400/21.0.7/jt400-21.0.7.jar
COPY src/Main.java src/Main.java
RUN javac -encoding UTF-8 -cp jt400.jar -d out src/Main.java

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/jt400.jar jt400.jar
COPY --from=build /app/out out
ENV PORT=8080
EXPOSE 8080
CMD ["java", "-cp", "out:jt400.jar", "Main"]
