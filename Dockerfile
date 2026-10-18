FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /app
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*
RUN curl -sSL -o jt400.jar https://repo1.maven.org/maven2/net/sf/jt400/jt400/21.0.7/jt400-21.0.7.jar
RUN curl -sSL -o tn5250j.jar https://github.com/tn5250j/tn5250j/releases/download/0.8.0-beta2/tn5250j-0.8.0-beta2-no-depedencies.jar
COPY src/Main.java src/Main.java
COPY src/Tn5250Run.java src/Tn5250Run.java
RUN javac -encoding UTF-8 -cp jt400.jar:tn5250j.jar -d out src/Main.java src/Tn5250Run.java

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /app/jt400.jar jt400.jar
COPY --from=build /app/tn5250j.jar tn5250j.jar
COPY --from=build /app/out out
ENV PORT=8080
EXPOSE 8080
CMD ["java", "-Djava.awt.headless=true", "-cp", "out:jt400.jar:tn5250j.jar", "Main"]
