package miniboxing.plugin

import scala.reflect.internal.Flags
import scala.tools.nsc.transform.TypingTransformers
import scala.collection.mutable.Set
import scala.tools.nsc.typechecker._
import scala.collection.mutable.{ Map => MMap }

trait MiniboxTreeSpecializer extends TypingTransformers {
  self: MiniboxComponent =>

  import global._
  import definitions._
  import Flags._
  import typer.{ typed, atOwner }

  def clearTypes(tree: Tree): Tree = {
    val ntree = tree.duplicate
    ntree.foreach(_.tpe = null)
    ntree
  }

  /** A tree transformer that prepares a tree for duplication */
  class MiniboxTreePreparer(unit: CompilationUnit,
                             oldThis: Symbol,
                             newThis: Symbol,
                             miniboxedArgs: Set[(Symbol, Type)],
                             miniboxedDeepEnv: Map[Symbol, Type],
                             miniboxedShallowEnv: Map[Symbol, Type],
                             miniboxedTags: Map[Symbol, Tree],
                             miniboxedReturn: Boolean) extends TypingTransformer(unit) {
    import global._
    override def transform(tree: Tree): Tree = tree match {
      case ddef@DefDef(mods, name, tparams, vparamss, tpt, rhs) =>
        localTyper.typed(deriveDefDef(ddef)(rhs => if (rhs != EmptyTree) miniboxReturn(boxArgs(rhs)) else EmptyTree))
      case vdef@ValDef(mods, name, tpt, rhs) =>
        localTyper.typed(deriveValDef(vdef)(rhs => if (rhs != EmptyTree) miniboxReturn(boxArgs(rhs)) else EmptyTree))
      case _ =>
        sys.error("incorrect use of miniboxed tree preparer")
    }

    val miniboxedArgSyms = miniboxedArgs.map(_._1)
    val miniboxedDeepEnvInv = miniboxedDeepEnv.map({ case (p, t) => (t.typeSymbol, p)})

    /** Wrap miniboxed arguments in minibox2box already */
    object boxArgs extends Transformer {
      val updateLabel: MMap[Symbol, Symbol] = MMap.empty
      def apply(tree: Tree) = transform(tree)
      override def transform(tree: Tree) = tree match {
        case i: Ident if miniboxedArgSyms.contains(i.symbol) =>
          val sym = i.symbol
          val tsp = miniboxedArgs.find(_._1 == sym).get._2
          val tp  = miniboxedDeepEnvInv(tsp.typeSymbol)
          val tag = miniboxedTags(tsp.typeSymbol)
          localTyper.typed(gen.mkMethodCall(minibox2box, List(tp.tpe), List(gen.mkAttributedIdent(sym), tag)))

        case Assign(lhs, rhs) if miniboxedArgSyms.contains(lhs.symbol) =>
          val sym = lhs.symbol
          val tsp = miniboxedArgs.find(_._1 == sym).get._2
          val tp  = miniboxedDeepEnvInv(tsp.typeSymbol)
          val tag = miniboxedTags(tsp.typeSymbol)
          val rhs1 = transform(rhs)
          localTyper.typed(Assign(clearTypes(lhs), gen.mkMethodCall(box2minibox, List(tp.tpe), List(rhs1, tag))))

        case sel@Select(qual@This(cl), name) if sel.symbol.isTerm && !sel.symbol.isMethod && (qual.symbol == oldThis) =>
          var tree1 = tree

          // pretty ugly, but sometime we need no rewiring, thus we just
          try {
            val tp = sel.symbol.tpe.typeSymbol
            val tsp = miniboxedDeepEnv(tp).typeSymbol
            val tsp_mb = miniboxedShallowEnv(tsp).typeSymbol
            assert((tp != LongClass) && (tsp_mb == LongClass)) // else fallback to the original tree
            val tag = miniboxedTags(tsp)
            // force the typer's hand a little, else we can't get to the specialized field:
            val mbr = newThis.info.member(name).filter(s => s.isTerm && !s.isMethod)
            val nqual = localTyper.typed(gen.mkAttributedThis(newThis)).setType(newThis.tpe)
            val nsel = gen.mkAttributedSelect(qual, mbr).setType(LongTpe)
            tree1 = localTyper.typed(gen.mkMethodCall(minibox2box, List(tp.tpe), List(nsel, tag)))
          } catch {
            case ex: Exception =>
          }
          tree1
        case _ =>
          super.transform(tree)
      }
    }

    /** Wrap the return type in a box2minibox if necessary */
    def miniboxReturn(tree: Tree): Tree =
      if (miniboxedReturn) {
        var modifiedLabelDefs: List[(Symbol, Symbol, Type)] = Nil

        def miniboxit(tree: Tree): Tree =
          tree match {
            case EmptyTree =>
              EmptyTree
            case Block(stats, res) =>
              // propagate inside blocks to enable the peephole optimization
              localTyper.typed(Block(stats, miniboxit(res)))
            case LabelDef(name, args, rhs) =>
              val label = tree.symbol
              val newlabel = label.cloneSymbol
              newlabel.modifyInfo({
                case MethodType(nargs, ret) =>
                  modifiedLabelDefs ::= (label, newlabel, ret)
                  MethodType(nargs, LongTpe)
              })
//              println("FROM: " + label.defString + " (SPEC)")
//              println("TO:   " + newlabel.defString + " (SPEC)")
              val newrhs = miniboxit(rhs.substituteSymbols(label.info.params, newlabel.info.params))
              val tree0 = LabelDef(newlabel.name, newlabel.info.params.map(s => Ident(s).setSymbol(s)), newrhs).setSymbol(newlabel)
              val tree1 = localTyper.typed(tree0)
//              println(tree1)
              tree1
            case _ =>
              val tp = tree.tpe
              val updatedTpe = miniboxedDeepEnv.getOrElse(tp.typeSymbol.deSkolemize, tp) // Nothing/Null stay the same
              val typeTag = miniboxedTags(updatedTpe.typeSymbol.deSkolemize)
              localTyper.typed(gen.mkMethodCall(box2minibox, List(tp), List(tree, typeTag)))
          }

        object fixLabelDefs extends TypingTransformer(unit) {
          override def transform(tree: Tree): Tree = {
            tree match {
              case Apply(lapply, args) if modifiedLabelDefs.exists(_._1 == lapply.symbol) =>
                val Some((_, newlabel, tpe)) = modifiedLabelDefs.find(_._1  == lapply.symbol)
                val updatedTpe = miniboxedDeepEnv.getOrElse(tpe.typeSymbol.deSkolemize, tpe)
                val typeTag = miniboxedTags(updatedTpe.typeSymbol.deSkolemize)
                localTyper.typed(gen.mkMethodCall(minibox2box, List(tpe), List(Apply(Ident(newlabel), args), typeTag)))
              case _ =>
                super.transform(tree)
            }
          }
        }

        val tree1 = miniboxit(tree)
        val tree2 = fixLabelDefs.transform(tree1)
        tree2
      } else
        tree
  }

  /** A tree transformer that transforms Tsp-s to Longs */
  class MiniboxTreeSpecializer(unit: CompilationUnit,
                                miniboxedSymsInit: List[(Symbol, Type)],
                                miniboxedTags: Map[Symbol, Tree],
                                miniboxedShallowEnv: Map[Symbol, Type]) extends TypingTransformer(unit) {

    // copied over from duplicators
    val shallowEnv = miniboxedShallowEnv.toList
    object miniboxedEnv extends SubstTypeMap(shallowEnv.map(_._1), shallowEnv.map(_._2)) {
      protected override def matches(sym1: Symbol, sym2: Symbol) =
        if (sym2.isTypeSkolem) sym2.deSkolemize eq sym1
        else sym1 eq sym2
    }

    var miniboxedSyms: List[(Symbol, Type)] = miniboxedSymsInit

    var indent = 0
    var treen = 0

    import global._
    import definitions._
    override def transform(tree: Tree): Tree = boxingTransform(tree)

    def boxingTransform(tree: Tree): Tree = {
      // printing vars:
      indent += 1
      treen += 1
      debug("  " * indent + "     (" + treen + ") tree: " + tree.toString.replaceAll("\n", "\n" + "  " * indent))

      val res = tree match {
        case vdef @ ValDef(mods, name, tpt, rhs) =>
          val tp = tree.symbol.tpe
          val tpm = miniboxedEnv(tp)
          if ((tpm =:= LongTpe) && !(tp =:= LongTpe)) {
            // TODO: Do this
            val rhs2 =
              if (rhs != EmptyTree)
                gen.mkMethodCall(box2minibox, List(tp), List(rhs, miniboxedTags(tp.typeSymbol)))
              else
                EmptyTree
            val tpt2 = tpt.setType(LongClass.tpe)
            var nvdef: Tree = copyValDef(vdef)(mods, name, tpt2, rhs2)
            nvdef.symbol = vdef.symbol.modifyInfo(miniboxedEnv)
            nvdef.tpe = null
            nvdef = localTyper.typed(nvdef)
            miniboxedSyms ::= (nvdef.symbol, tp)
            nvdef
            localTyper.typed(nvdef)
          } else {
            localTyper.typed(deriveValDef(vdef)(boxingTransform))
          }

        case i: Ident if miniboxedSyms.exists(_._1 == i.symbol) =>
          val sym = i.symbol
          val tsp = miniboxedSyms.find(_._1 == sym).get._2
          val tag = miniboxedTags(tsp.typeSymbol)
          localTyper.typed(gen.mkMethodCall(minibox2box, List(tsp), List(gen.mkAttributedIdent(sym), tag)))

        case Assign(lhs, rhs) if miniboxedSyms.exists(_._1 == lhs.symbol) =>
          val sym = lhs.symbol
          val tsp = miniboxedSyms.find(_._1 == sym).get._2
          val tag = miniboxedTags(tsp.typeSymbol)
          val rhs1 = transform(rhs)
          localTyper.typed(Assign(clearTypes(lhs), gen.mkMethodCall(box2minibox, List(tsp), List(rhs1, tag))))

        // don't touch DefDefs, LabelDef, ClassDef-s YET...
        // -- we don't care about TypeDefs, ModuleDefs and PackageDefs since they do not take parameters
        case d: DefDef =>
          treeCopy.DefDef(d, d.mods, d.name, d.tparams, d.vparamss, d.tpt, transform(d.rhs))
        case l: LabelDef =>
          treeCopy.LabelDef(l, l.name, l.params, transform(l.rhs))
        case c: ClassDef =>
          treeCopy.ClassDef(c, c.mods, c.name, c.tparams, transformTemplate(c.impl))
        case other => super.transform(other)
      }
      debug("  " * indent + "     (" + treen + ") res:  " + res.toString.replaceAll("\n", "\n" + "  " * indent))
      indent -= 1
      res
    }
  }
}
