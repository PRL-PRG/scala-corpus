package cz.cvut.fit.prl.scala.implicits.extractor

import cz.cvut.fit.prl.scala.implicits.model._
import cz.cvut.fit.prl.scala.implicits.utils._

import scala.collection.mutable
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.{semanticdb => s}
import scala.util.{Failure, Success, Try}

/**
  * An extraction context that keeps track of declarations and call sites extracted in
  * a **single** module.
  *
  * @param moduleId id of the module this extractor tracks
  * @param resolver a resolver aware of the module
  * @param sourcePaths a list of source paths used in the given module
  */
class ExtractionContext(
    moduleId: String,
    resolver: SemanticdbSymbolResolver,
    val sourcePaths: List[String])
    extends SymbolResolver {

  private val declarationIndex: mutable.Map[String, Try[Declaration]] = mutable.Map()
  private var callSiteId: Int = 0
  private val localDeclarationIndex: mutable.Map[(String, String), String] = mutable.Map()
  private val sourcePathIndex: mutable.Map[String, Option[String]] = mutable.Map()

  private def scalaNothing(implicit db: TextDocument) = resolveDeclaration(declarationIds.Nothing)
  private def scalaAny(implicit db: TextDocument) = resolveDeclaration(declarationIds.Any)

  def declarations: Iterable[Declaration] = declarationIndex.collect {
    case (_, Success(declaration)) => declaration
  }

  def createCallSiteId: Int = {
    callSiteId = callSiteId + 1
    callSiteId
  }

  def sourcePath(uri: String): Option[String] = {
    sourcePathIndex.getOrElseUpdate(
      uri,
      sourcePaths.collectFirst { case x if uri.startsWith(x) => x }
    )
  }

  def relaxedSourcePath(uri: String): String = sourcePath(uri).getOrElse("")

  /**
    * Resolves a declaration.
    *
    * All requests for declarations from any extraction tool must come through here.
    *
    * @param declarationId a declaration id
    * @param db: a document withing which we are resolving the declaration, needed for local declaration
    * @return a resolved declaration or throws an exception
    */
  def resolveDeclaration(declarationId: String)(implicit db: s.TextDocument): Declaration = {
    val id = if (declarationId.isLocal && !declarationId.contains(BlockLocalIdSep)) {
      val key = (db.uri, declarationId)

      localDeclarationIndex.getOrElseUpdate(key, {
        val size = localDeclarationIndex.size + 1
        declarationId + BlockLocalIdSep + size
      })
    } else {
      declarationId
    }

    // There can be cycles in TypeSignature (e.g. class X[A <: X[A]])
    // because of that we first put into the declarationIndex a prototype
    // of the final declaration which will come later.
    // This is safe as long as the only thing that is used is the
    // declarationId.
    def create(): Unit = {
      val resolvedSymbol = resolveSymbol(declarationId)
      val symbolInfo = resolvedSymbol.symbolInfo

      try {
        val prototype =
          createDeclaration(symbolInfo).copy(
            declarationId = id,
            location = resolvedSymbol.location
          )

        declarationIndex += id -> Success(prototype)

        val signature = createSignature(symbolInfo.signature, symbolInfo.kind, id)
        val annotations =
          symbolInfo.annotations.map(x => createType(x.tpe))

        val declaration = prototype.withSignature(signature).withAnnotations(annotations)

        declarationIndex += id -> Success(declaration)
      } catch {
        case e: Throwable =>
          declarationIndex += id -> Failure(e)
      }
    }

    declarationIndex.get(id) match {
      case Some(Success(declaration)) => declaration
      case Some(Failure(exception)) => throw exception
      case None =>
        create()
        declarationIndex(id).get
    }
  }

  override def resolveSymbol(name: String)(implicit db: s.TextDocument): ResolvedSymbol = {
    resolver.resolveSymbol(name)
  }

  override def resolveSymbol(range: s.Range)(implicit db: s.TextDocument): ResolvedSymbol =
    resolver.resolveSymbol(range)

  private def createTypeSignature(
      signature: s.TypeSignature
  )(implicit db: s.TextDocument): TypeSignature = {

    val typeParameters =
      signature.typeParameters.symbols.map(resolveDeclaration(_).typeRef)

    val upperBound = createTypeOpt(signature.upperBound, includeTopBottom = false)

    val lowerBound = createTypeOpt(signature.lowerBound, includeTopBottom = false)

    TypeSignature(
      typeParameters = typeParameters,
      upperBound = upperBound,
      lowerBound = lowerBound
    )
  }

  private def createClassSignature(
      signature: s.ClassSignature
  )(implicit db: s.TextDocument): ClassSignature = {
    val typeParameters =
      signature.typeParameters.symbols.map(resolveDeclaration(_).typeRef)
    val parents =
      signature.parents
        .map(x => createTypeOpt(x, includeTopBottom = false))
        .collect {
          case Some(v) => v
        }

    ClassSignature(typeParameters = typeParameters, parents = parents)
  }

  private def createMethodSignature(signature: s.MethodSignature, kind: s.SymbolInformation.Kind, declarationId: String)(
      implicit db: s.TextDocument): MethodSignature = {

    val typeParameters = signature.typeParameters.symbols.map(resolveDeclaration(_).typeRef)
    val parameterLists = signature.parameterLists.map(createParameterList)
    val returnType =
      if (kind.isConstructor) {
        TypeRef(declarationId)
      } else {
        createType(signature.returnType)
      }

    MethodSignature(
      typeParameters = typeParameters,
      parameterLists = parameterLists,
      returnType = returnType
    )
  }

  private def createValueSignature(signature: s.ValueSignature)(
      implicit db: s.TextDocument): ValueSignature = {
    ValueSignature(createType(signature.tpe))
  }

  private def createSignature(signature: s.Signature, kind: s.SymbolInformation.Kind, declarationId: String)(implicit db: s.TextDocument): Signature =
    signature match {
      case x: s.TypeSignature => createTypeSignature(x)
      case x: s.ClassSignature => createClassSignature(x)
      case x: s.MethodSignature => createMethodSignature(x, kind, declarationId)
      case x: s.ValueSignature => createValueSignature(x)
      case _ =>
        throw new ElementNotSupportedException("signature", signature.getClass.getSimpleName)
    }

  private def createDeclaration(
      symbolInfo: s.SymbolInformation
  ): Declaration = {
    import Declaration.Kind._

    val location = null
    val kind = symbolInfo match {
      case x if x.isVal => VAL
      case x if x.isVar => VAR
      case x if x.isType => TYPE
      case x if x.isClass => CLASS
      case x if x.isTrait => TRAIT
      case x if x.isObject => OBJECT
      case x if x.isInterface => INTERFACE
      case x if x.isEnum => ENUM
      case x if x.isMethod => DEF
      case x if x.isConstructor => DEF
      case x if x.isTypeParameter => TYPE_PARAMETER
      case x if x.isMacro => MACRO
      case x if x.isParameter => PARAMETER
      case x => throw new ElementNotSupportedException("declaration symbol kind", x.kind)
    }

    import Declaration.Access._

    val access = symbolInfo.access match {
      case _: s.PrivateAccess => PRIVATE
      case _: s.PrivateThisAccess => PRIVATE_THIS
      case _: s.PrivateWithinAccess => PROTECTED_WITHIN
      case _: s.ProtectedAccess => PROTECTED
      case _: s.ProtectedThisAccess => PROTECTED_THIS
      case _: s.ProtectedWithinAccess => PROTECTED_WITHIN
      case _: s.PublicAccess => PUBLIC
      case s.Access.Empty => NOT_SPECIFIED
      case x => throw new ElementNotSupportedException("declaration symbol access", x)
    }

    Declaration(
      declarationId = symbolInfo.symbol,
      moduleId = moduleId,
      kind = kind,
      properties = symbolInfo.properties,
      name = symbolInfo.displayName,
      location = location,
      language = symbolInfo.language,
      access = access,
      signature = Signature.Empty
    )
  }

  private def createParameterList(scope: s.Scope)(implicit db: s.TextDocument): ParameterList = {
    // TODO: this will fail for hardlinks
    val parameters = scope.symbols.map(resolveSymbol).map(x => createParameter(x.symbolInfo))
    ParameterList(parameters)
  }

  private def createParameter(symbolInfo: s.SymbolInformation)(
      implicit db: s.TextDocument): Parameter =
    symbolInfo.signature match {
      case s.ValueSignature(tpe) =>
        Parameter(
          symbolInfo.displayName,
          createType(tpe),
          symbolInfo.isImplicit
        )
      case x =>
        throw ElementNotExpectedException("parameter signature", x.getClass.getSimpleName)
    }

  def createType(tpe: s.Type)(implicit db: s.TextDocument): TypeRef =
    createTypeOpt(tpe, includeTopBottom = true) match {
      case Some(v) => v
      case None =>
        throw new Exception(s"Unable to create type from $tpe")
    }

  def createTypeOpt(tpe: s.Type, includeTopBottom: Boolean)(
      implicit db: s.TextDocument): Option[TypeRef] =
    tpe match {
      case x: s.TypeRef if !includeTopBottom && x.isTopOrBottom =>
        None
      case s.TypeRef(_, symbol, typeArguments) =>
        // we do not support dependent types hence no prefix handling
        Some(createTypeReference(symbol, typeArguments))
      case s.AnnotatedType(_, t) =>
        // we do not support annotated types
        // cf. https://www.scala-lang.org/files/archive/spec/2.12/03-types.html#annotated-types
        createTypeOpt(t, includeTopBottom)
      case s.SingleType(_, symbol) =>
        val parent = resolveDeclaration(symbol)
        Some(TypeRef(parent.declarationId))
      case s.ByNameType(t) =>
        createTypeOpt(t, includeTopBottom)
      case s.RepeatedType(repeatedType) =>
        Some(createTypeReference("scala/Array#", Seq(repeatedType)))
      case s.ExistentialType(s.TypeRef(p, t, _), _) =>
        // e.g. Class[T] forSome { type T }
        // we ignore the arguments for the existentials
        // otherwise we will have to deal with the scope and hard links, not worth the trouble
        createTypeOpt(s.TypeRef(p, t), includeTopBottom)
      case s.UniversalType(_, t) =>
        // TODO: is this ok?
        createTypeOpt(t, includeTopBottom)
      case s.StructuralType(t, _) =>
        createTypeOpt(t, includeTopBottom)
      case s.WithType(types) =>
        // TODO: is this ok?
        createTypeOpt(types.head, includeTopBottom)
      case s.ThisType(symbol) =>
        Some(TypeRef(resolveDeclaration(symbol).declarationId))
      case s.Type.Empty =>
        None
      case x =>
        throw new ElementNotSupportedException("type", x.getClass.getSimpleName)
    }

  def createTypeArguments(typeArguments: Seq[s.Type], includeTopBottom: Boolean)(
      implicit db: s.TextDocument): List[TypeRef] = {
    // TODO: this one will silently ignore some type resolution errors
    typeArguments
      .map(x => Try(createTypeOpt(x, includeTopBottom)))
      .collect {
        case scala.util.Success(Some(x)) => x
      }
      .toList
  }

  private def createTypeReference(symbol: String, typeArguments: Seq[s.Type])(
      implicit db: s.TextDocument): TypeRef = {
    resolveSymbol(symbol).symbolInfo match {
      case x
          if x.isTypeParameter || x.isType || x.isClass || x.isTrait || x.isInterface || x.isObject =>
        val declaration = resolveDeclaration(symbol)
        TypeRef(
          declaration.declarationId,
          createTypeArguments(typeArguments, includeTopBottom = false))
      case x =>
        throw new ElementNotSupportedException("TypeRef type", x)
    }
  }
}
