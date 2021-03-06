name := "paradise-ng"

lazy val commonSettings = Def.settings(
    crossVersion := CrossVersion.full,
    crossScalaVersions := Seq("2.11.11", "2.11.12",
                              "2.12.3",  "2.12.4",  "2.12.5", "2.12.6"),
    libraryDependencies += "org.scalameta" %% "scalameta" % "3.3.0"
)

lazy val nonPublishable = Def.settings(
    publishArtifact := false,
    publish := { }
)

// RUNTIME LIBRARY /////////////////////

lazy val paradiseNgLib = (project in file("lib")).settings(commonSettings)

// PLUGIN //////////////////////////////

lazy val paradiseNgPluginCode = (project in file("plugin")).
    dependsOn(paradiseNgLib).
    settings(
        commonSettings,
        nonPublishable,
        libraryDependencies +=
            scalaOrganization.value % "scala-compiler" % scalaVersion.value,
        assemblyOption.in(assembly) ~= { _.copy(includeScala = false) },
        assemblyJarName.in(assembly) := (name.value + "_"
            + scalaVersion.value + "-" + version.value + "-assembly.jar"),
    )

// The description of the jar file where the plugin resides.
lazy val jar = assembly in paradiseNgPluginCode

// Compiler options needed to enable a compiler plugin.
def pluginOptions(jar: File): Seq[String] = Seq(
    "-Xplugin:" + jar,
    "-Jdummy="  + jar.lastModified
)

lazy val paradiseNgPlugin = project.settings(
    commonSettings, packageBin in Compile := jar.value)

/* Make it possible to find the classpath and plugin path that are necessary for
   testing the REPL in runtime. */
def exposePaths(pluginJar: File, classpath: Seq[File]) {
    System.setProperty("sbt.paths.plugin.jar", pluginJar.getAbsolutePath)
    System.setProperty("sbt.paths.repl.test.classes",
        classpath.map(_.getAbsolutePath).mkString(java.io.File.pathSeparator))
}

// TESTS ///////////////////////////////////////////////////////////////////////

lazy val testSettings = Def.settings(
    commonSettings,
    nonPublishable,
    libraryDependencies += "org.scalatest" %% "scalatest" % "3.0.1",
    scalacOptions in Test ++= pluginOptions(jar.value)
)

// TESTS OF COMPILE-TIME EXPANSIONS ///

lazy val expansionTests = (project in file("tests/expansion")).
    dependsOn(paradiseNgLib).
    settings(testSettings)

// TESTS OF THE REPL ///

lazy val replTests = (project in file("tests/repl")).
    dependsOn(paradiseNgLib).
    settings(
        testSettings,
        libraryDependencies +=
            scalaOrganization.value % "scala-compiler" % scalaVersion.value,
        fullClasspath in Test := {
            val defaultValue = (fullClasspath in Test).value
            exposePaths(jar.value, defaultValue.files)
            defaultValue
        }
    )

// AGGREGATE TEST PROJECT /////////////

lazy val tests = project.aggregate(expansionTests, replTests)
    .settings(testSettings)

// MAIN PROJECT ///////////////////////////////////////////////////////////////

lazy val `paradise-ng` = (project in file(".")).dependsOn(paradiseNgLib).
    aggregate(paradiseNgLib, paradiseNgPlugin, tests).
    settings(nonPublishable, commonSettings,
        scalacOptions ++= pluginOptions(jar.value))
