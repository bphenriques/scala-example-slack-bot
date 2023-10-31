Global / onChangedBuildSource := ReloadOnSourceChanges

val catsEffectV = "3.5.2"

val catsV = "2.10.0"

val cirisV = "3.4.0"

val circeV = "0.14.6"

val fs2V = "3.9.2"

val http4sMunitVersion = "0.15.1"

val http4sV = "1.0.0-M40"

val log4catsV = "2.6.0"

val logbackLogstashV = "7.4"

val logbackV = "1.4.11"

val logstashV = "7.4"

val munitCatsEffectV = "2.0.0-M3"

val munitV = "0.7.29"

val slackV = "1.35.0"

val slf4jV = "2.0.9"

lazy val loggingConfiguration = Seq(
  libraryDependencies ++= Seq(
    "org.slf4j"            % "slf4j-api"                % slf4jV,
    "org.slf4j"            % "log4j-over-slf4j"         % slf4jV,
    "org.slf4j"            % "jcl-over-slf4j"           % slf4jV,
    "org.slf4j"            % "jul-to-slf4j"             % slf4jV,
    "org.typelevel"       %% "log4cats-core"            % log4catsV,
    "org.typelevel"       %% "log4cats-slf4j"           % log4catsV,
    "net.logstash.logback" % "logstash-logback-encoder" % logbackLogstashV % Runtime,
    "ch.qos.logback"       % "logback-classic"          % logbackV         % Runtime,
  ),
  excludeDependencies ++= Seq(
    ExclusionRule("commons-logging", "commons-logging"),
    ExclusionRule("org.slf4j", "slf4j-log4j12"),
    ExclusionRule("log4j", "log4j"),
    ExclusionRule("org.apache.logging.log4j", "log4j-core"),
    ExclusionRule("org.apache.logging.log4j", "log4j-slf4j-impl"),
  ),
)

inThisBuild(
  List(
    scalaVersion     := "2.13.12",
    version          := "0.1.0-SNAPSHOT",
    organization     := "com.bphenriques",
    organizationName := "Bruno Henriques",
    javacOptions ++= Seq("-source", "17", "-target", "17"), // Wait until Cats-Effect support JVM 21
    addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
  ) ++ loggingConfiguration,
)

lazy val root = (project in file("."))
  .aggregate(app)

lazy val app = (project in file("modules/app"))
  .settings(
    moduleName               := "app",
    name                     := moduleName.value,
    coverageFailOnMinimum    := true,
    coverageMinimumStmtTotal := 10, // FIXME
    libraryDependencies ++= Seq(
      "com.slack.api"        % "slack-api-client"    % slackV,
      "is.cir"              %% "ciris"               % cirisV,
      "org.typelevel"       %% "cats-core"           % catsV,
      "org.typelevel"       %% "cats-effect"         % catsEffectV,
      "io.circe"            %% "circe-parser"        % circeV,
      "io.circe"            %% "circe-literal"       % circeV,
      "org.http4s"          %% "http4s-ember-client" % http4sV, // required to get access to signature
      "org.http4s"          %% "http4s-ember-server" % http4sV,
      "org.http4s"          %% "http4s-dsl"          % http4sV,
      "org.http4s"          %% "http4s-circe"        % http4sV,
      "co.fs2"              %% "fs2-core"            % fs2V,
      "co.fs2"              %% "fs2-io"              % fs2V,
      "org.scalameta"       %% "munit"               % munitV % Test,
      "org.typelevel"       %% "munit-cats-effect"   % munitCatsEffectV % Test,
      "com.alejandrohdezma" %% "http4s-munit"        % http4sMunitVersion % Test,
    ),
  )
