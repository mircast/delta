import play.PlayImport.PlayKeys._

name := "delta"

organization := "io.flow"

scalaVersion in ThisBuild := "2.11.8"

// required because of issue between scoverage & sbt
parallelExecution in Test in ThisBuild := true

val awsVersion = "1.11.19"

lazy val generated = project
  .in(file("generated"))
  .enablePlugins(PlayScala)
  .enablePlugins(SbtWeb)
  .settings(commonSettings: _*)
  .settings(
    libraryDependencies ++= Seq(
      ws
    )
  )

lazy val lib = project
  .in(file("lib"))
  .dependsOn(generated)
  .aggregate(generated)
  .settings(commonSettings: _*)
  .settings(
    libraryDependencies ++= Seq(
      "org.yaml" % "snakeyaml" % "1.17"
    )
  )

lazy val api = project
  .in(file("api"))
  .dependsOn(generated, lib)
  .aggregate(generated, lib)
  .enablePlugins(PlayScala)
  .enablePlugins(NewRelic)
  .settings(commonSettings: _*)
  .settings(
    routesImport += "io.flow.delta.v0.Bindables._",
    routesGenerator := InjectedRoutesGenerator,
    libraryDependencies ++= Seq(
      ws,
      jdbc,
      "io.flow" %% "lib-postgresql" % "0.0.32",
      "org.scalatestplus" %% "play" % "1.4.0" % "test",
      "com.amazonaws" % "aws-java-sdk-ecs" % awsVersion,
      "com.amazonaws" % "aws-java-sdk-elasticloadbalancing" % awsVersion,
      "com.amazonaws" % "aws-java-sdk-autoscaling" % awsVersion,
      "org.postgresql" % "postgresql" % "9.4.1208",
      "com.sendgrid"   %  "sendgrid-java" % "3.0.4",
      "org.scalatestplus" %% "play" % "1.4.0" % "test"
    )
  )

lazy val www = project
  .in(file("www"))
  .dependsOn(generated, lib)
  .aggregate(generated, lib)
  .enablePlugins(PlayScala)
  .enablePlugins(NewRelic)
  .enablePlugins(SbtWeb)
  .settings(commonSettings: _*)
  .settings(
    routesImport += "io.flow.delta.v0.Bindables._",
    routesGenerator := InjectedRoutesGenerator,
    libraryDependencies ++= Seq(
      "org.webjars" %% "webjars-play" % "2.5.0",
      "org.webjars" % "bootstrap" % "3.3.6",
      "org.webjars.bower" % "bootstrap-social" % "5.0.0",
      "org.webjars" % "font-awesome" % "4.6.3",
      "org.webjars" % "jquery" % "2.1.4"
    )
  )

val credsToUse = Option(System.getenv("ARTIFACTORY_USERNAME")) match {
  case None => Credentials(Path.userHome / ".ivy2" / ".artifactory")
  case _ => Credentials("Artifactory Realm","flow.artifactoryonline.com",System.getenv("ARTIFACTORY_USERNAME"),System.getenv("ARTIFACTORY_PASSWORD"))
}


lazy val commonSettings: Seq[Setting[_]] = Seq(
  name <<= name("delta-" + _),
  libraryDependencies ++= Seq(
    "io.flow" %% "lib-play" % "0.1.37",
    specs2 % Test,
    "org.scalatest" %% "scalatest" % "2.2.6" % "test"
  ),
  sources in (Compile,doc) := Seq.empty,
  publishArtifact in (Compile, packageDoc) := false,
  scalacOptions += "-feature",
  coverageHighlighting := true,
  resolvers += "Typesafe repository" at "http://repo.typesafe.com/typesafe/releases/",
  resolvers += "scalaz-bintray" at "https://dl.bintray.com/scalaz/releases",
  resolvers += "Artifactory" at "https://flow.artifactoryonline.com/flow/libs-release/",
  credentials += Credentials(
    "Artifactory Realm",
    "flow.artifactoryonline.com",
    System.getenv("ARTIFACTORY_USERNAME"),
    System.getenv("ARTIFACTORY_PASSWORD")
  )
)

version := "0.2.16"
