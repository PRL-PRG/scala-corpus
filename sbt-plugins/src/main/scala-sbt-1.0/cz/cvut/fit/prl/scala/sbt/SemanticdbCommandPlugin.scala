package cz.cvut.fit.prl.scala.sbt

import sbt.Keys._
import sbt._
import sbt.internal.inc.Analysis
import sbt.librarymanagement.CrossVersion
import sbt.plugins.JvmPlugin
import Config._

// This is based on code provided Olaf Geirsson (https://github.com/olafurpg)
object SemanticdbCommandPlugin extends AutoPlugin {
  override def requires: Plugins = JvmPlugin

  override def trigger: PluginTrigger = allRequirements

  def relevantProjects(state: State): Seq[(ProjectRef, String)] = {
    val extracted = Project.extract(state)
    for {
      p <- extracted.structure.allProjectRefs
      version <- scalaVersion.in(p).get(extracted.structure.data).toList
      partialVersion <- CrossVersion.partialVersion(version).toList
      fullVersion <- VersionMapping_sbt_1_0.get(partialVersion).toList
    } yield p -> fullVersion
  }

  val compileAll = taskKey[Unit]("compile all projects in test+compile configs")

  override def globalSettings = List(
    aggregate.in(compileAll) := false,
    compileAll := Def.taskDyn {
      val refs = relevantProjects(state.value).map(_._1)
      val filter = ScopeFilter(
        projects = inProjects(refs: _*),
        configurations = inConfigurations(Compile, Test))
      compile.all(filter)
    }.value,
    commands += Command.command("semanticdb") { s =>
      println(">> SEMANTICDB OPTIONS: "+SemanticdbScalacOptions)
      val extracted = Project.extract(s)
      val toCompile = List.newBuilder[TaskKey[Analysis]]
      val refs = List.newBuilder[ProjectRef]
      val settings: Seq[Setting[_]] = for {
        (p, fullVersion) <- relevantProjects(s)
        setting <- List(
          scalaVersion.in(p) := fullVersion,
          scalacOptions.in(p) ++= SemanticdbScalacOptions,
          libraryDependencies.in(p) += compilerPlugin(
            "org.scalameta" % "semanticdb-scalac" % ScalametaVersion cross CrossVersion.full)
        )
      } yield setting
      val installed = extracted.append(settings, s)
      "compileAll" ::
        installed
    }
  )
}