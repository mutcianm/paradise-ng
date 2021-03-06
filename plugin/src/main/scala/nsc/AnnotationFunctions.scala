package com.github.dkhalansky.paradiseng.plugin
import com.github.dkhalansky.paradiseng.lib._

/*  This trait is devoted to acquiring an instance of `TreeTransformation`
    represented by an annotation. */
trait AnnotationFunctions { self: ParadiseNgComponent =>

    import global._

    abstract class TreeEvaluationError() { }
    object TreeEvaluationError {
        case class VarValueDependency(varTree: Tree) extends TreeEvaluationError
        case class AnnotatedDependency(tree: Tree) extends TreeEvaluationError
        case class NoSourceError(tree: Tree) extends TreeEvaluationError
        case class UnsupportedTree(tree: Tree) extends TreeEvaluationError
    }

    /*  Given a symbol, determine the string to feed to the classloader to
        acquire the corresponding class.

        Copy-pasted blindly from
        `scala/src/compiler/scala/tools/nsc/typechecker/Macros.scala` */
    private def getClassNameBySymbol(symbol: Symbol) = {
        def loop(sym: Symbol): String = sym match {
            case sym if sym.isTopLevel =>
                val suffix = if (sym.isModule || sym.isModuleClass) "$" else ""
                sym.fullName + suffix
            case sym =>
                val separator = if (sym.owner.isModuleClass) "" else "$"
                loop(sym.owner) + separator + sym.javaSimpleName.toString
        }
        loop(symbol)
    }

    private lazy val loader = Reflect.findMacroClassLoader(
        global.classPath.asURLs, global.settings.outputDirs.getSingleOutput)

    /*  Try following a chain of assignments of the form `val a = b`,
        `val b = c`, `val c = d`... to eventually find a constant value.

        @tree -- the tree to be resolved.

        @maxDepth -- the maximal length of a chain. */
    @annotation.tailrec
    private def getParameter(tree: Tree):
    Either[TreeEvaluationError, Any] = tree match {
        case Literal(Constant(v)) => Right(v)
        case s if s.symbol != null && s.symbol != NoSymbol =>
            s.symbol.source match {
                case Some(t@ValDef(m, _, _, v)) => {
                    if (m hasFlag Flag.MUTABLE) {
                        Left(TreeEvaluationError.VarValueDependency(t))
                    } else if (!m.annotations.isEmpty) {
                        Left(TreeEvaluationError.AnnotatedDependency(t))
                    } else {
                        getParameter(v)
                    }
                }
                case _ => Left(TreeEvaluationError.NoSourceError(tree))
            }
        case _ => Left(TreeEvaluationError.UnsupportedTree(tree))
    }

    /*  From annotation defition acquire an instance of the annotation class. */
    def getAnnotationFunction(annotation: AnnotationInfo) = {
        // This means that the macro function hasn't been compiled yet.
        if (currentRun.compiles(annotation.tpe.typeSymbol)) {
            AnnotationFromCurrentRunError(annotation)
        }

        /*  Sometimes a typer can create fake trees that aren't part of the tree
            it's assigning types to. The case this code is concerned with is the
            following: when an annotation (or any constructor, really) has
            named arguments, the compiler can transform

                A(b = 4, a = 3)

            into

                {
                    val x$2 = 3
                    val x$1 = 4
                    A(x$2, x$1)
                }

            or something. It is a problem because while it works for normal
            constructors, annotations are not preserved in their tree form and
            are rather converted into an `AnnotationInfo`, which have a very
            strict form and therefore can't reflect in any way that they were
            in a block form.

            So, the annotation which went through this transformation looks
            like an `AnnotationInfo` that has as its arguments references to
            mysterious variables of the form `x$1`, `x$2` and so on, and there
            are no definitions of such variables in the typed tree itself.

            Luckily, the block such as the one above is stored in the `original`
            field of the `AnnotationInfo`. The symbols assigned to it are
            obviously the same ones as in the annotation, so attaching the
            sources of the `original` to them solves the problem. */
        attachSourcesToSymbols(annotation.original)

        /*  First, we try to resolve for each argument whether it hides a type
            parameter that we shadowed before doing anything else with the
            tree. If it does, we retrieve it. Otherwise, we try to resolve the
            argument into a constant value. */
        val args = annotation.args.map(xs => {
            unhideTypeParam(xs) match {
                case Some(t) => t
                case None => getParameter(xs) match {
                    case Right(v) => v
                    case Left(e) => ParameterResolutionError(xs.pos, e)
                }
            }
        })

        val cls_name = getClassNameBySymbol(annotation.tpe.typeSymbol)
        try Reflect.instantiate[TreeTransformation](loader, cls_name, args)
        catch {
            case e: java.lang.NoSuchMethodException =>
                NoConstructorError(annotation, args)
        }
    }
}
