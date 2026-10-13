# GraphNous Java Scanner

Scans the Java sources of a Maven project and writes a
[GraphNous scan result](https://graphnous.github.io/graphnous-schemas/schema/scan/scan-result.schema.json):
its modules, files, classes, methods and fields, and for each method the
methods it calls, the fields it reads and writes and the types it uses.

The Graphnous server runs it on the repositories it analyses; it can also
be run on its own, as a jar or as an image.

## Running

```sh
docker run --rm \
  -v "$PWD:/repository:ro" -v "$PWD/out:/output" \
  ghcr.io/graphnous/graphnous-java-scanner:0.1.0 \
  --path /repository --target . --output /output/scan-result.json
```

or, after [building](#building) it:

```sh
java -jar java-scanner-app/target/java-scanner.jar \
  --path . --target . --output scan-result.json
```

| Argument | |
|---|---|
| `--path <dir>` | The repository to scan. Required. |
| `--target <path>` | The project in the repository, relative to it. Default `.`. |
| `--output <file>` | Where to write the scan result. Required. |
| `--java-version <version>` | The Java version of the project, e.g. `21`, which decides the syntax its sources are parsed with. Default: the newest supported. |
| `--classpath <jars>` | Jars of the libraries of the project, separated by `:` (`;` on Windows), e.g. as `mvn dependency:build-classpath` lists them. |
| `--maven-repository <dir>` | The local Maven repository to find the libraries in. Default `/maven-repository` when it exists, else `~/.m2/repository`. |
| `--download-dependencies` | Download the libraries missing from the local Maven repository. |
| `--maven-remote <url>` | Where to download from without Maven settings. Default Maven Central. |
| `--maven-settings <file>` | Maven settings with the repositories to download from. Default `/maven-settings/settings.xml` when it exists. |
| `--verbose` | Log details, such as each file scanned and the libraries found. |

## What it finds

- The Maven modules of the project, with their dependencies, and the Java
  files of each, as production or test code.
- The classes of each file, including member, local and anonymous classes;
  local and anonymous classes have the binary name javac gives them, e.g.
  `com.example.Outer$1`.
- Their methods, constructors, fields, enum constants, record components,
  annotations, supertypes, type parameters and line and column positions.
- For each method body: its calls, field accesses and type uses, resolved
  to the qualified name of what they refer to, e.g.
  `com.example.Orders.find(java.lang.String)`. What cannot be resolved is
  kept as written, with `resolved` set to `false`.
- For each call: its arguments as written, each on one line and cut off at
  200 characters, in the order written.

Calls into a library resolve only when the scanner has the jars of the
project's libraries; see below. Without them, calls into the JDK and the
project's own code still resolve.

## Libraries

The scanner looks for the jars of the libraries the project uses in:

1. The jars passed with `--classpath`.
2. The local Maven repository: the dependencies of the modules' poms and
   the dependencies those pull in, as Maven resolves them, including
   versions managed by parents and imported BOMs, and exclusions.
3. The sources the build generated in `target/generated-sources`, which
   are used to resolve types but not scanned.

Library code is not parsed up front: a jar is indexed by the names of its
classes, and a class is only read when the scanned code refers to it.

### Downloading them

With `--download-dependencies`, the poms and jars missing from the local
Maven repository are downloaded into it, from Maven Central by default.
Only files are downloaded, each checked against the `.sha1` the repository
publishes: no Maven and no plugin of the project runs. Keep the repository
on a volume so the next scan finds them:

```sh
docker run --rm \
  -v "$PWD:/repository:ro" -v "$PWD/out:/output" \
  -v graphnous-maven:/maven-repository \
  ghcr.io/graphnous/graphnous-java-scanner:0.1.0 \
  --path /repository --output /output/scan-result.json --download-dependencies
```

The scan container needs to reach the repository it downloads from, e.g.
`repo.maven.apache.org`.

A repository a build already filled can be mounted instead, read-only and
without downloading:

```sh
  -v "$HOME/.m2/repository:/maven-repository:ro"
```

### Private repositories

To download from a private repository, such as a company Nexus or
Artifactory, mount a Maven `settings.xml` at `/maven-settings/settings.xml`,
or pass it with `--maven-settings`. The scanner uses:

- the `mirrors`, e.g. a mirror of `*` in place of Maven Central;
- the `repositories` of the active `profiles`, then Maven Central;
- the credentials of the `servers` with the same id: `username` and
  `password`, or the `httpHeaders` of their `configuration` for tokens.

`${env.NAME}` in the settings is read from the environment, so the file
needs no secrets:

```xml
<settings>
  <servers>
    <server>
      <id>nexus</id>
      <username>scanner</username>
      <password>${env.NEXUS_PASSWORD}</password>
    </server>
  </servers>
  <mirrors>
    <mirror>
      <id>nexus</id>
      <mirrorOf>*</mirrorOf>
      <url>https://nexus.example.com/repository/maven-public/</url>
    </mirror>
  </mirrors>
</settings>
```

```sh
  -v "$PWD/settings.xml:/maven-settings/settings.xml:ro" \
  -e NEXUS_PASSWORD=... \
  ... --download-dependencies
```

Credentials are only sent to their repository's own host, not where it
redirects to. Repositories the poms of the scanned project declare are
never used, so a project cannot receive the credentials by naming a
repository after one in the settings. Encrypted passwords, the `proxies`
of the settings (the JVM's proxy settings apply) and `-SNAPSHOT` versions
from remote repositories are not supported.

## Building

Requires Java 25.

```sh
./mvnw verify
```

The scan result model is generated from the schema at
`graphnous.schema.url` in `pom.xml`; to build against another copy, pass
`-Dgraphnous.schema.url=file:///path/to/scan-result.schema.json`.

The jar is `java-scanner-app/target/java-scanner.jar`. Pushing a `v*` tag,
e.g. `v0.1.0`, publishes the image as
`ghcr.io/graphnous/graphnous-java-scanner:0.1.0`.

### A note on JavaParser

The scanner parses and resolves with JavaParser.
`java-scanner-app/src/main/java/com/github/javaparser/.../BlockStmtContext.java`
replaces the class of the same name in JavaParser, which made resolving the
names of long methods take minutes; see the comment at its top for when it
can be removed.
