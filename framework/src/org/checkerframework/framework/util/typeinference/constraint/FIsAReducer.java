package org.checkerframework.framework.util.typeinference.constraint;

import com.sun.tools.javac.code.Type;
import org.checkerframework.framework.type.AnnotatedTypeFactory;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.AnnotatedTypeMirror.*;
import org.checkerframework.framework.type.DefaultTypeHierarchy;
import org.checkerframework.framework.type.visitor.AbstractAtmComboVisitor;
import org.checkerframework.framework.util.AnnotatedTypes;
import org.checkerframework.framework.util.PluginUtil;
import org.checkerframework.framework.util.typeinference.TypeArgInferenceUtil;

import javax.lang.model.type.TypeKind;
import java.util.List;
import java.util.Set;

/**
 * FIsAReducer takes an FIsA constraint that is not irreducible (@see AFConstraint.isIrreducible)
 * and reduces it by one step.  The resulting constraint may still be reducible.
 *
 * Generally reductions should map to corresponding rules in
 * http://docs.oracle.com/javase/specs/jls/se7/html/jls-15.html#jls-15.12.2.7
 */
public class FIsAReducer implements AFReducer {

    protected final FIsAReducingVisitor visitor;
    private final AnnotatedTypeFactory typeFactory;

    public FIsAReducer(final AnnotatedTypeFactory typeFactory) {
        this.typeFactory = typeFactory;
        this.visitor = new FIsAReducingVisitor();
    }

    @Override
    public boolean reduce(AFConstraint constraint, Set<AFConstraint> newConstraints, Set<AFConstraint> finished) {
        if (constraint instanceof FIsA) {
            final FIsA fIsA = (FIsA) constraint;
            visitor.visit(fIsA.argument, fIsA.formalParameter, newConstraints);
            return true;

        } else {
            return false;
        }
    }


    /**
     * Given an FIsA constraint of the form:
     * FIsA( typeFromFormalParameter, typeFromMethodArgument )
     *
     * FIsAReducingVisitor visits the constraint as follows:
     * visit ( typeFromFormalParameter, typeFromMethodArgument, newConstraints )
     *
     * The visit method will determine if the given constraint should either:
     *    a) be discarded - in this case, the visitor just returns
     *    b) reduced to a simpler constraint or set of constraints - in this case, the new constraint
     *    or set of constraints is added to newConstraints
     *
     *  From the JLS, in general there are 2 rules that govern F = A constraints:
     *  If F = Tj, then the constraint Tj <: A is implied.
     *  If F = U[], where the type U involves Tj, then if A is an array type V[], or a type variable with an
     *  upper bound that is an array type V[], where V is a reference type, this algorithm is applied recursively
     *  to the constraint V >> U. Otherwise, no constraint is implied on Tj.
     *
     *  Since both F and A may have component types this visitor delves into their components
     *  and applies these rules to the components.  However, only one step is taken at a time (i.e. this
     *  is not a scanner)
     */
    class FIsAReducingVisitor extends AbstractAtmComboVisitor<Void, Set<AFConstraint>> {
        @Override
        protected String defaultErrorMessage(AnnotatedTypeMirror argument, AnnotatedTypeMirror parameter, Set<AFConstraint> afConstraints) {
            return "Unexpected FIsA Combination:\b"
                 + "argument=" + argument + "\n"
                 + "parameter=" + parameter + "\n"
                 + "constraints=[\n" + PluginUtil.join(", ", afConstraints) + "\n]";
        }
        //------------------------------------------------------------------------
        //Arrays as arguments

        @Override
        public Void visitArray_Array(AnnotatedArrayType parameter, AnnotatedArrayType argument, Set<AFConstraint> constraints) {
            constraints.add(new FIsA(parameter.getComponentType(), argument.getComponentType()));
            return null;
        }

        @Override
        public Void visitArray_Declared(AnnotatedArrayType parameter, AnnotatedDeclaredType argument, Set<AFConstraint> constraints) {
            return null;
        }

        @Override
        public Void visitArray_Null(AnnotatedArrayType parameter, AnnotatedNullType argument, Set<AFConstraint> constraints) {
            return null;
        }

        @Override
        public Void visitArray_Wildcard(AnnotatedArrayType parameter, AnnotatedWildcardType argument, Set<AFConstraint> constraints) {
            constraints.add(new FIsA(parameter, argument.getExtendsBound()));
            return null;
        }

        //------------------------------------------------------------------------
        //Declared as argument
        @Override
        public Void visitDeclared_Array(AnnotatedDeclaredType argument, AnnotatedArrayType parameter, Set<AFConstraint> constraints) {
            //should this be Array<String> - T[] the new A2F(String, T)
            return null;
        }

        @Override
        public Void visitDeclared_Declared(AnnotatedDeclaredType argument, AnnotatedDeclaredType parameter, Set<AFConstraint> constraints) {
            if (argument.wasRaw() || parameter.wasRaw()) {
                return null;
            }

            AnnotatedDeclaredType argumentAsParam = DefaultTypeHierarchy.castedAsSuper(argument, parameter);
            if (argumentAsParam == null) {
                return null;
            }

            final List<AnnotatedTypeMirror> argTypeArgs = argumentAsParam.getTypeArguments();
            final List<AnnotatedTypeMirror> paramTypeArgs = parameter.getTypeArguments();
            for (int i = 0; i < argTypeArgs.size(); i++) {
                final AnnotatedTypeMirror argTypeArg = argTypeArgs.get(i);
                final AnnotatedTypeMirror paramTypeArg = paramTypeArgs.get(i);

                if (paramTypeArg.getKind() == TypeKind.WILDCARD) {
                    final AnnotatedWildcardType paramWc = (AnnotatedWildcardType) paramTypeArg;

                    if (argTypeArg.getKind() == TypeKind.WILDCARD) {
                        final AnnotatedWildcardType argWc = (AnnotatedWildcardType) argTypeArg;
                        constraints.add(new FIsA(paramWc.getExtendsBound(), argWc.getExtendsBound()));
                        constraints.add(new FIsA(paramWc.getSuperBound(),   argWc.getSuperBound()));
                    }

                } else {
                    constraints.add(new FIsA(paramTypeArgs.get(i), argTypeArgs.get(i)));

                }
            }

            return null;
        }

        @Override
        public Void visitDeclared_Null(AnnotatedDeclaredType argument, AnnotatedNullType parameter, Set<AFConstraint> constraints) {
            return null;
        }

        @Override
        public Void visitDeclared_Primitive(AnnotatedDeclaredType argument, AnnotatedPrimitiveType parameter, Set<AFConstraint> constraints) {
            return null;
        }

        @Override
        public Void visitDeclared_Union(AnnotatedDeclaredType argument, AnnotatedUnionType parameter, Set<AFConstraint> constraints) {
            return null;  //TODO: NOT SUPPORTED AT THE MOMENT
        }

        @Override
        public Void visitIntersection_Intersection(AnnotatedIntersectionType argument, AnnotatedIntersectionType parameter, Set<AFConstraint> constraints) {
            return null;  //TODO: NOT SUPPORTED AT THE MOMENT
        }


        @Override
        public Void visitIntersection_Null(AnnotatedIntersectionType argument, AnnotatedNullType parameter, Set<AFConstraint> constraints) {
            return null;
        }

        //------------------------------------------------------------------------
        //Primitive as argument
        @Override
        public Void visitPrimitive_Declared(AnnotatedPrimitiveType argument, AnnotatedDeclaredType parameter, Set<AFConstraint> constraints) {
            //we may be able to eliminate this case, since I believe the corresponding constraint will just be discarded
            //as the parameter must be a boxed primitive
            constraints.add(new A2F(typeFactory.getBoxedType(argument), parameter));
            return null;
        }

        @Override
        public Void visitPrimitive_Primitive(AnnotatedPrimitiveType argument, AnnotatedPrimitiveType parameter, Set<AFConstraint> constraints) {
            return null;
        }

        @Override
        public Void visitTypevar_Typevar(AnnotatedTypeVariable argument, AnnotatedTypeVariable parameter, Set<AFConstraint> constraints) {
            //if we've reached this point and the two are corresponding type variables, then they are NOT ones that
            //may have a type variable we are inferring types for and therefore we can discard this constraint
            if (!AnnotatedTypes.areCorrespondingTypeVariables(typeFactory.getElementUtils(), argument, parameter)) {
                constraints.add(new FIsA(argument, parameter));
            }

            return null;
        }


        @Override
        public Void visitTypevar_Null(AnnotatedTypeVariable argument, AnnotatedNullType parameter, Set<AFConstraint> constraints) {
            return null;
        }
    }
}