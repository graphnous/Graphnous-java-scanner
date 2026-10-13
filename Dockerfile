# The Java scanner as an image. Its entrypoint runs the scanner, so
# arguments go straight to it:
#
#   docker run --rm -v "$PWD:/repository:ro" -v "$PWD/out:/output" \
#     ghcr.io/graphnous/graphnous-java-scanner:0.1.0 \
#     --path /repository --target . --output /output/scan-result.json
#
# To resolve calls into the libraries of the project, the scanner reads
# their jars from the Maven repository at /maven-repository. Mount a volume
# there and add --download-dependencies to have the scanner download what
# it misses from Maven Central (--maven-remote for another repository);
# the volume keeps them for the next scan:
#   -v graphnous-maven:/maven-repository ... --download-dependencies
# Or mount a repository a build filled, read-only, without downloading:
#   -v "$HOME/.m2/repository:/maven-repository:ro"
# Or pass the jars, e.g. as mvn dependency:build-classpath lists them:
#   --classpath a.jar:b.jar
#
# The Graphnous server instead overrides the entrypoint with the full
# command from its configuration: java -jar /opt/graphnous/java-scanner.jar ...

FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /build

COPY pom.xml ./
COPY java-scanner-app/pom.xml java-scanner-app/
RUN mvn -B -q -pl java-scanner-app dependency:go-offline

COPY java-scanner-app/src java-scanner-app/src
RUN mvn -B -q package -DskipTests


FROM eclipse-temurin:25-jre

COPY --from=build /build/java-scanner-app/target/java-scanner.jar /opt/graphnous/java-scanner.jar

# Scans run as an unprivileged user; the repository is mounted read-only
RUN useradd --system --uid 10001 --no-create-home scanner \
    && mkdir /maven-repository \
    && chown scanner /maven-repository
USER scanner

ENTRYPOINT ["java", "-jar", "/opt/graphnous/java-scanner.jar"]
