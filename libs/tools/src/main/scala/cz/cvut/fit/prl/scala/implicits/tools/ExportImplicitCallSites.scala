package cz.cvut.fit.prl.scala.implicits.tools

import better.files._

import com.typesafe.scalalogging.Logger

import cz.cvut.fit.prl.scala.implicits.model._
import cz.cvut.fit.prl.scala.implicits.model.Util.timedTask

import kantan.csv._
import kantan.csv.ops._
import kantan.csv.generic._
import org.slf4j.LoggerFactory

object ExportImplicitCallSites extends ExportApp {

  implicit val logger = Logger(LoggerFactory.getLogger(getClass.getName))

  /**
    *
    * @param local - 0 - not local, 1 - module local, 2 - project local
    */
  case class Output(
      projectId: String,
      groupId: String,
      artifactId: String,
      version: String,
      code: String,
      declarationId: String,
      declarationKind: String,
      declarationGroupId: String,
      declarationArtifactId: String,
      declarationVersion: String,
      local: Int,
      locationPath: String,
      locationUri: String,
      locationPos: String,
      numTypeArguments: Int,
      numImplicitArguments: Int
  )

  object Output {
    val Header = Seq(
      "project_id",
      "group_id",
      "artifact_id",
      "version",
      "code",
      "declaration_id",
      "declaration_kind",
      "declaration_group_id",
      "declaration_artifact_id",
      "declaration_version",
      "local",
      "location_path",
      "location_uri",
      "location_pos",
      "num_type_arguments",
      "num_implicit_arguments"
    )

    def apply(callSite: CallSite)(implicit idx: Index): Output = {
      val project = callSite.project
      val module = callSite.module
      val library = callSite.library
      val declaration = callSite.declaration
      val declarationLibrary = declaration.library
      val local =
        if (library == declarationLibrary) {
          1
        } else if (library.groupId == declarationLibrary.groupId) {
          2
        } else {
          0
        }

      Output(
        projectId = project.projectId,
        groupId = module.groupId,
        artifactId = module.artifactId,
        version = module.version,
        code = callSite.code,
        declarationId = declaration.declarationId,
        declarationKind = declaration.kind.name,
        declarationGroupId = declarationLibrary.groupId,
        declarationArtifactId = declarationLibrary.artifactId,
        declarationVersion = declarationLibrary.version,
        local = local,
        locationPath = callSite.location.path,
        locationUri = callSite.location.relativeUri,
        locationPos =
          callSite.location.position.map(x => x.startLine + ":" + x.startCol).getOrElse("NA"),
        numTypeArguments = callSite.typeArguments.size,
        numImplicitArguments = callSite.implicitArgumentTypes.size
      )
    }
  }

  private def export(idx: Index, outputFile: File): Unit = {
    for {
      out <- outputFile.newOutputStream.autoClosed
      writer <- out.asCsvWriter[Output](rfc.withHeader(Output.Header: _*)).autoClosed
      callSite <- idx.implicitCallSites
      row = Output(callSite)(idx)
    } writer.write(row)
  }

  def run(idx: Index, outputFile: File): Unit = {
    timedTask(
      s"Exporting ${idx.implicitCallSites.size} call sites into $outputFile",
      export(idx, outputFile)
    )
  }

  def defaultOutputFilename = "implicit-callsites.csv"
}
