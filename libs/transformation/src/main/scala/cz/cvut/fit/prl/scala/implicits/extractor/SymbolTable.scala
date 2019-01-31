package cz.cvut.fit.prl.scala.implicits.extractor

import cz.cvut.fit.prl.scala.implicits.model.Location
import cz.cvut.fit.prl.scala.implicits.utils.BuildInfo

import scala.collection.concurrent.TrieMap
import scala.meta.cli._
import scala.meta.internal.classpath._
import scala.meta.internal.metacp._
import scala.meta.internal.scalacp.Scalalib
import scala.meta.internal.semanticdb.Language.SCALA
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolInformation.Kind.PACKAGE
import scala.meta.io._
import scala.meta.metacp._
import scala.reflect.NameTransformer

case class ResolvedSymbol(symbolInfo: SymbolInformation, location: Location)

trait SymbolTable {
  def resolve(symbol: String): Option[ResolvedSymbol]
}

// based on the scalameta semtab
class GlobalSymbolTable(classpath: Classpath) extends SymbolTable {

  private val settings = Settings()
  private val reporter = Reporter().withSilentOut().withSilentErr()
  private val classpathIndex = ClasspathIndex(classpath)
  private val symbolCache = TrieMap.empty[String, ResolvedSymbol]

  private val scalaLibraryLocation = {
    Location("_synthetics_", "_synthetics_", None)
  }

  Scalalib.synthetics.foreach(
    x => enter(x, scalaLibraryLocation)
  )

  override def resolve(symbol: String): Option[ResolvedSymbol] = {
    if (symbol.isNone) None
    else if (symbol.isPackage) {
      if (symbol.isRootPackage ||
          symbol.isEmptyPackage ||
          classpathIndex.isClassdir(symbol)) {

        val location = {
          val tmp = classpathIndex.dirs
            .get(symbol)
            .flatMap(_.members.collectFirst {
              case (_, UncompressedClassfile(_, path)) =>
                Location(path.toFile.getAbsolutePath, symbol)
              case (_, CompressedClassfile(_, path)) =>
                Location(path.getAbsolutePath, symbol)
            })

          tmp.getOrElse(Location("_package_", symbol))
        }

        val info = SymbolInformation(
          symbol = symbol,
          kind = PACKAGE,
          language = SCALA,
          displayName = symbol.desc.value
        )

        Some(ResolvedSymbol(info, location))
      } else {
        None
      }
    } else {
      symbolCache.get(symbol) match {
        case Some(x) =>
          Some(x)
        case None =>
          loadSymbol(symbol)
          symbolCache.get(symbol)
      }
    }
  }

  private def loadSymbol(symbol: String): Unit = {
    val toplevel = symbol.ownerChain.find(!_.isPackage).get
    val owner = toplevel.owner
    val classdir = if (owner.isEmptyPackage) "/" else owner
    var filename = NameTransformer.encode(toplevel.desc.value) + ".class"
    classpathIndex.getClassfile(classdir, filename) match {
      case Some(classfile) =>
        val node = classfile.toClassNode
        ClassfileInfos.fromClassNode(node, classpathIndex, settings, reporter) match {
          case Some(infos) =>
            val location = createLocation(classfile)
            enter(infos, location)
          case _ =>
            ()
        }
      case _ =>
        ()
    }
  }

  private def enter(infos: ClassfileInfos, location: Location): Unit =
    infos.infos.foreach { info =>
      symbolCache(info.symbol) = ResolvedSymbol(info, location)
    }

  def createLocation(classfile: Classfile): Location =
    classfile match {
      case UncompressedClassfile(relativeUri, path) =>
        val fullpath = path.toFile.getAbsolutePath
        val dir =
          fullpath.substring(0, fullpath.length - relativeUri.length - 1)
        Location(dir, relativeUri)
      case CompressedClassfile(entry, zipFile) =>
        Location(zipFile.getAbsolutePath, entry.getName)
    }
}

object GlobalSymbolTable {
  def apply(classpath: Classpath): GlobalSymbolTable = {
    new GlobalSymbolTable(classpath)
  }
}
