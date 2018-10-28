package cz.cvut.fit.prl.scala.implicits

object Constants {
  val VersionsFilename = "metadata-versions.csv"


  val ClasspathsFilename = "metadata-classpaths.csv"
  val SourcepathsFilename = "metadata-sourcepaths.csv"

  lazy val ExcludedDirs: Seq[String] = Seq(".git", AnalysisDirname)
  val ExtractedImplicitsFilename = "implicits.bin"
  val ProjectsDirname = "projects"
  val AnalysisDirname = "_analysis_"
  val PerProjectMergedSemanticdbFilename = "semanticdbs-4.0.0.bin"
  val PerProjectMergedSemanticdbStatsFilename = "semanticdbs-4.0.0.csv"
  val MergedSemanticdbFilename = "merged-semanticdbs.bin"
}
