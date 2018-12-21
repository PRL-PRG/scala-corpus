package cz.cvut.fit.prl.scala.implicits.metadata

import cz.cvut.fit.prl.scala.implicits.metadata.Constants.PathSep

case class SLOC(files: String, language: String, blank: String, comment: String, code: String) {
  override def toString = s"$files,$language,$blank,$comment,$code"
}

object SLOC {
  val CsvHeader: Seq[String] = Seq("files", "language", "blank", "comment", "code")
}

case class SourcePath(
    projectId: String,
    moduleId: String,
    scope: String,
    managed: Boolean,
    path: String,
    sloc: SLOC)

object SourcePath {
  val CsvHeader: Seq[String] = Seq("project_id", "module_id", "scope", "managed") ++ SLOC.CsvHeader
}

case class Version(
    projectId: String,
    moduleId: String,
    groupId: String,
    artifactId: String,
    version: String,
    commit: String,
    scalaVersion: String,
    sbtVersion: String,
    updatedScalaVersion: String,
    outputClasspath: String,
    outputTestClasspath: String) {
  val outputClasspaths: Seq[String] = outputClasspath.split(PathSep)
  val outputTestClasspaths: Seq[String] = outputTestClasspath.split(PathSep)
}

object Version {
  val CsvHeader: Seq[String] = Seq(
    "project_id",
    "module_id",
    "group_id",
    "artifact_id",
    "version",
    "commit",
    "scala_version",
    "sbt_version",
    "updated_scala_version",
    "output_classpath",
    "output_test_classpath")
}

case class InternalDependency(
    projectId: String,
    moduleId: String,
    dependency: String,
    dependencyGroupId: String,
    dependencyArtifactId: String,
    dependencyVersion: String,
    scope: String)

object InternalDependency {
  val CsvHeader: Seq[String] =
    Seq(
      "project_id",
      "module_id",
      "dependency",
      "dependency_group_id",
      "dependency_artifact_id",
      "dependency_version",
      "scope")
}

case class ExternalDependency(
    projectId: String,
    moduleId: String,
    groupId: String,
    artifactId: String,
    version: String,
    path: String,
    scope: String)

object ExternalDependency {
  val CsvHeader: Seq[String] =
    Seq("project_id", "module_id", "group_id", "artifact_id", "version", "path", "scope")
}

case class CleanPath(projectId: String, moduleId: String, path: String)

object CleanPath {
  val CsvHeader: Seq[String] =
    Seq("project_id", "module_id", "path")
}