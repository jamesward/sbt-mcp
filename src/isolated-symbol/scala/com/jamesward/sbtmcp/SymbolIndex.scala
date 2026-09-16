package com.jamesward.sbtmcp

import scala.collection.mutable
import scala.util.control.NonFatal

import tastyquery.Contexts.Context
import tastyquery.Symbols.*


/**
 * Symbol search & inspection over TASTy via tasty-query.
 *
 * STUB fidelity notes (compiled against tasty-query 1.8; binary-compatible with
 * the isolated tasty-query 1.9 reader):
 *  - `glob-search` walks packages from the root and matches the unqualified name
 *    case-insensitively (Metals matches the last FQN segment at a name boundary;
 *    we approximate with a boundary-aware `startsWith`/equality plus `contains`
 *    fallback). Results are capped.
 *  - Symbol "kind" is coarse (class/term/type/package); precise object/trait
 *    disambiguation via flags is deferred.
 *  - `inspect` renders member signatures with `declaredType.toString`; this is a
 *    readable approximation, not a pretty-printed Scala signature.
 */
object SymbolIndex {

  final case class Hit(kind: String, fqn: String)

  def globSearch(query: String, inPackage: Option[String] = None, limit: Int = 100)(using
      ctx: Context
  ): List[Hit] = {
    val q       = query.toLowerCase.trim
    val listAll = q.isEmpty || q == "*"
    val out     = mutable.ListBuffer.empty[Hit]

    def matches(name: String): Boolean =
      listAll || {
        val n = name.toLowerCase
        n == q || n.startsWith(q) || n.contains(q)
      }

    def fqn(sym: Symbol): String =
      try sym.displayFullName
      catch { case NonFatal(_) => sym.toString }

    def kindOf(cls: ClassSymbol): String =
      if (cls.isModuleClass) "object" else if (cls.isTrait) "trait" else "class"

    def visit(sym: Symbol): Unit = {
      if (out.size >= limit) return
      try
        sym match {
          case pkg: PackageSymbol =>
            pkg.declarations.foreach(visit)
          case cls: ClassSymbol =>
            if (matches(cls.name.toString)) out += Hit(kindOf(cls), fqn(cls))
            // ClassSymbol.declarations is List[TermOrTypeSymbol] (sealed:
            // TermSymbol | TypeSymbol), so these cases are exhaustive.
            cls.declarations.foreach {
              case nested: ClassSymbol => visit(nested)
              case t: TermSymbol       => if (matches(t.name.toString)) out += Hit("method", fqn(t))
              case ty: TypeSymbol      => if (matches(ty.name.toString)) out += Hit("type", fqn(ty))
            }
          case t: TermSymbol =>
            if (matches(t.name.toString)) out += Hit("term", fqn(t))
          case ty: TypeSymbol =>
            if (matches(ty.name.toString)) out += Hit("type", fqn(ty))
        }
      catch { case NonFatal(_) => () } // some symbols fail to force; skip them
    }

    val roots: List[Symbol] =
      inPackage match {
        case Some(pkg) =>
          try List(ctx.findPackage(pkg))
          catch { case NonFatal(_) => Nil } // unknown package => no results
        case None => List(ctx.defn.RootPackage)
      }
    roots.foreach(visit)
    out.toList.distinct
  }

  def inspect(fqn: String)(using ctx: Context): Option[String] = {
    val cls = findClass(fqn)
    cls.map { c =>
      val header = s"${c.name} — $fqn"
      val members =
        try
          c.declarations.map {
            case t: TermSymbol  => s"  ${t.name}: ${signatureOf(t)}"
            case ty: TypeSymbol => s"  type ${ty.name}"
          }
        catch { case NonFatal(e) => List(s"  <could not read members: ${e.getMessage}>") }
      (header +: members).mkString("\n")
    }
  }

  /**
   * Source location of a symbol as `path:line`, read from the symbol's defining
   * tree position in TASTy. Works for classes/objects/traits and static/top-level
   * terms. Returns None if the symbol isn't found or its position is unknown (e.g.
   * a Java symbol, or TASTy compiled without positions).
   */
  def location(fqn: String)(using ctx: Context): Option[String] = {
    val sym: Option[Symbol] =
      findClass(fqn).orElse {
        try Some(ctx.findStaticTerm(fqn))
        catch { case NonFatal(_) => None }
      }
    sym.flatMap { s =>
      s.tree.flatMap { t =>
        val pos = t.pos
        if (pos.isUnknown) None
        else Some(s"${pos.sourceFile.path}:${pos.startLine + 1}")
      }
    }
  }

  private def findClass(fqn: String)(using ctx: Context): Option[ClassSymbol] = {
    def attempt(f: => ClassSymbol): Option[ClassSymbol] =
      try Some(f) catch { case NonFatal(_) => None }
    attempt(ctx.findTopLevelClass(fqn))
      .orElse(attempt(ctx.findTopLevelModuleClass(fqn)))
      .orElse(attempt(ctx.findStaticClass(fqn)))
  }

  private def signatureOf(t: TermSymbol): String =
    try t.declaredType.toString
    catch { case NonFatal(_) => "<unknown>" }
}
