package cz.cvut.fit.prl.scala.implicits.extractor

import cz.cvut.fit.prl.scala.implicits.model.{Declaration, DeclarationResolver, Type, TypeResolver}
import cz.cvut.fit.prl.scala.implicits.symtab.ResolvedSymbol
import cz.cvut.fit.prl.scala.implicits.utils._
import cz.cvut.fit.prl.scala.implicits.{model => m}

import scala.collection.mutable
import scala.meta.internal.semanticdb.Scala._
import scala.meta.internal.{semanticdb => s}
import scala.util.Try

class ExtractionContext(resolver: SemanticdbSymbolResolver)
    extends TypeResolver
    with SymbolResolver
    with DeclarationResolver {

  private val declarationIndex: mutable.Map[String, Declaration] = mutable.Map()

  def resolveDeclaration(symbol: String): m.Declaration =
    resolveDeclaration(resolveSymbol(symbol))

  def declarations: Seq[Declaration] = declarationIndex.values.toSeq

  override def resolveType(tpe: Type): Declaration =
    declarationIndex(tpe.ref.fqn)

  override def resolveSymbol(symbol: String): ResolvedSymbol =
    resolver.resolveSymbol(symbol)

  override def resolveSymbol(unit: String, range: s.Range): ResolvedSymbol =
    resolver.resolveSymbol(unit, range)

  private def resolveSymbolInfo(symbol: String): s.SymbolInformation =
    resolver.resolveSymbol(symbol).symbolInfo

  private def resolveDeclaration(resolvedSymbol: ResolvedSymbol): m.Declaration = {
    val symbolInfo = resolvedSymbol.symbolInfo
    declarationIndex.get(symbolInfo.symbol) match {
      case Some(x) => x
      case None    =>
        // this will be just a temporary one to prevent infinite
        // loop in the case a TypeSignature contains a cycle
        // (e.g. class X[A <: X[A]])
        // The actual value of the key will be replaced later, but
        // that should be safe since we only call .ref which is
        // fixed from the symbol
        val prototype =
          createDeclaration(symbolInfo)
            .withLocation(resolvedSymbol.location)

        declarationIndex += symbolInfo.symbol -> prototype

        val signature = createSignature(symbolInfo.signature)
        val declaration = prototype.withSignature(signature)

        declarationIndex += symbolInfo.symbol -> declaration
        declaration
    }
  }

  private def createTypeSignature(
      signature: s.TypeSignature
  ): m.TypeSignature = {

    val typeParameters =
      signature.typeParameters.symbols.map(createTypeParameter)

    m.TypeSignature(typeParameters = typeParameters)
  }

  private def createTypeSignature(
      signature: s.ClassSignature
  ): m.TypeSignature = {
    val typeParameters =
      signature.typeParameters.symbols.map(createTypeParameter)
    val parents = signature.parents.map(createType).filterNot(_.isEmpty)

    m.TypeSignature(typeParameters = typeParameters, parents = parents)
  }

  private def createMethodSignature(
      signature: s.MethodSignature
  ): m.MethodSignature = {

    val typeParameters =
      signature.typeParameters.symbols.map(createTypeParameter)
    val parameterLists = signature.parameterLists.map(createParameterList)
    val returnType = createType(signature.returnType)

    m.MethodSignature(
      typeParameters = typeParameters,
      parameterLists = parameterLists,
      returnType = returnType
    )
  }

  private def createValueSignature(signature: s.ValueSignature): m.ValueSignature = {
    m.ValueSignature(createType(signature.tpe))
  }

  private def createSignature(signature: s.Signature): m.Declaration.Signature =
    signature match {
      case x: s.TypeSignature =>
        m.Declaration.Signature.Type(createTypeSignature(x))
      case x: s.ClassSignature =>
        m.Declaration.Signature.Type(createTypeSignature(x))
      case x: s.MethodSignature =>
        m.Declaration.Signature.Method(createMethodSignature(x))
      case x: s.ValueSignature =>
        m.Declaration.Signature.Value(createValueSignature(x))
      case _ =>
        throw UnsupportedElementException("signature", signature.getClass.getSimpleName)
    }

  private def createDeclaration(
      symbolInfo: s.SymbolInformation
  ): m.Declaration = {
    import m.Declaration.Kind._

    val location = null
    val kind = symbolInfo match {
      case x if x.isVal           => VAL
      case x if x.isVar           => VAR
      case x if x.isType          => TYPE
      case x if x.isClass         => CLASS
      case x if x.isTrait         => TRAIT
      case x if x.isObject        => OBJECT
      case x if x.isInterface     => INTERFACE
      case x if x.isEnum          => ENUM
      case x if x.isMethod        => DEF
      case x if x.isConstructor   => DEF
      case x if x.isTypeParameter => TYPE_PARAMETER
      case x if x.isMacro         => MACRO
      case x if x.isParameter     => PARAMETER
      case x                      => throw UnsupportedElementException("declaration symbol kind", x.kind)
    }

    m.Declaration(
      kind = kind,
      fqn = symbolInfo.symbol,
      name = symbolInfo.displayName,
      location = location,
      language = symbolInfo.language,
      isImplicit = symbolInfo.isImplicit,
    )
  }

  private def createParameterList(scope: s.Scope): m.ParameterList = {
    val parameters = scope.symbols.map(resolveSymbolInfo).map(createParameter)
    m.ParameterList(parameters)
  }

  private def createParameter(symbolInfo: s.SymbolInformation): m.Parameter =
    symbolInfo.signature match {
      case s.ValueSignature(tpe) =>
        m.Parameter(
          symbolInfo.displayName,
          createType(tpe),
          symbolInfo.isImplicit
        )
      case x =>
        throw UnexpectedElementException("parameter signature", x.getClass.getSimpleName)
    }

  private def createTypeParameter(symbol: String): m.TypeParameter =
    createTypeParameter(resolveSymbolInfo(symbol))

  private def createTypeParameter(
      symbolInfo: s.SymbolInformation
  ): m.TypeParameter = symbolInfo.signature match {
    case s.TypeSignature(typeParameters, lowerBound, upperBound) =>
      m.TypeParameter(
        name = symbolInfo.displayName,
        typeParameters = typeParameters.symbols.map(createTypeParameter),
        lowerBound = Try(createType(lowerBound)).getOrElse(m.Type.Empty),
        upperBound = Try(createType(upperBound)).getOrElse(m.Type.Empty)
      )
    case x =>
      throw UnexpectedElementException("type parameter signature", x.getClass.getSimpleName)
  }

  def createType(tpe: s.Type): m.Type = tpe match {
    case x: s.TypeRef if x.isTopOrBottom => m.Type.Empty
    // TODO: do we need to do anything prefix?
    case s.TypeRef(_, symbol, typeArguments) =>
      createTypeReference(symbol, typeArguments)
    case s.AnnotatedType(annotations, t) =>
      // TODO: annotation
      createType(t)
    case s.SingleType(prefix, symbol) =>
      val parent = resolveDeclaration(symbol)
      m.TypeRef(parent.ref)
    case s.ByNameType(tpe) =>
      createType(tpe)
    case s.RepeatedType(repeatedType) =>
      createTypeReference("scala/Array#", Seq(repeatedType))
    case s.Type.Empty => m.Type.Empty
    case x =>
      throw UnsupportedElementException("type", x.getClass.getSimpleName)
  }

  private def createTypeReference(symbol: String, typeArguments: Seq[s.Type]): m.Type = {
    resolveSymbolInfo(symbol) match {
      case x if x.isTypeParameter =>
        val parent = resolveDeclaration(symbol.owner)
        m.TypeParameterRef(
          parent.ref,
          x.displayName,
          typeArguments.map(createType)
        )
      case x if x.isType || x.isClass || x.isTrait || x.isInterface =>
        val parent = resolveDeclaration(symbol)
        m.TypeRef(parent.ref, typeArguments.map(createType))
      case x if x.symbol.isLocal =>
        throw UnsupportedElementException("TypeRef type", "local")
      case x =>
        throw UnsupportedElementException("TypeRef type", x)
    }
  }
}
