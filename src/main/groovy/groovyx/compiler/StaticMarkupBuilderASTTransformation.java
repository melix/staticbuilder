/*
 * Copyright 2003-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package groovyx.compiler;

import groovy.lang.DelegatesTo;
import groovyx.runtime.AbstractTag;
import groovyx.transform.StaticMarkupBuilderGenerator;
import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.runtime.StringGroovyMethods;
import org.codehaus.groovy.syntax.Token;
import org.codehaus.groovy.syntax.Types;
import org.codehaus.groovy.transform.AbstractASTTransformation;
import org.codehaus.groovy.transform.GroovyASTTransformation;

import java.io.OutputStream;
import java.util.*;

@GroovyASTTransformation(phase = CompilePhase.SEMANTIC_ANALYSIS)
public class StaticMarkupBuilderASTTransformation extends AbstractASTTransformation {

    private static final String ANNOTATION_NAME = StaticMarkupBuilderGenerator.class.getSimpleName();
    private static final ClassNode ABSTRACT_TAG = ClassHelper.make(AbstractTag.class);
    private static final ClassNode OUTPUTSTREAM_TYPE = ClassHelper.make(OutputStream.class);
    private static final Options DEFAULT_OPTIONS = new Options(true, Collections.<String>emptySet());
    private static final ClassNode ATTRIBUTE_MAP_TYPE = ClassHelper.MAP_TYPE.getPlainNodeReference();

    static {
        final GenericsType[] gt = new GenericsType[2];
        gt[0] = new GenericsType(ClassHelper.STRING_TYPE);
        gt[1] = new GenericsType(ClassHelper.STRING_TYPE);
        ATTRIBUTE_MAP_TYPE.setGenericsTypes(gt); // Map<String,String>
    }

    private SourceUnit sourceUnit;
    private ClassNode classNode;
    private final Map<String, InnerClassNode> tagNameToClass = new HashMap<String, InnerClassNode>();


    @Override
    public void visit(final ASTNode[] nodes, final SourceUnit source) {
        init(nodes, source);
        sourceUnit = source;
        AnnotatedNode parent = (AnnotatedNode) nodes[1];
        checkClass(parent);
        classNode = (ClassNode) parent;
        addSuperClass(classNode);
        addConstructor(classNode);
        createTagClasses();
    }

    private void createTagClasses() {
        FieldNode tags = classNode.getField("schema");
        if (tags == null) {
            return;
        }
        Expression initialExpression = tags.getInitialExpression();
        if (initialExpression == null || (!(initialExpression instanceof ClosureExpression))) {
            addError(ANNOTATION_NAME + " requires a [tags] field with a tag schema", tags);
            return;
        }
        translateSchema(classNode, (ClosureExpression)initialExpression);
        tags.setInitialValueExpression(null);
        classNode.removeField("schema"); // doesn't work?
    }

    private void addConstructor(final ClassNode cn) {
        Parameter[] parameters = new Parameter[]{
                new Parameter(OUTPUTSTREAM_TYPE, "out")
        };
        ConstructorCallExpression cce = new ConstructorCallExpression(
                ClassNode.SUPER,
                new ArgumentListExpression(new VariableExpression(parameters[0]))
        );
        ConstructorNode cons = new ConstructorNode(
                ACC_PUBLIC,
                parameters,
                ClassNode.EMPTY_ARRAY,
                new ExpressionStatement(cce)
        );
        cn.addConstructor(cons);
    }

    private void addSuperClass(final ClassNode cn) {
        cn.setSuperClass(ABSTRACT_TAG);
    }

    private void checkClass(final AnnotatedNode node) {
        if (!(node instanceof ClassNode)) {
            addError("Only class nodes may be annotated with " + ANNOTATION_NAME, node);
        }
        ClassNode cn = (ClassNode) node;
        ClassNode superClass = cn.getSuperClass();
        if (superClass != null && !ClassHelper.OBJECT_TYPE.equals(superClass)) {
            addError(ANNOTATION_NAME + " requires a class without superclass but " + cn.getName() + " extends " + superClass.getName(), node);
            return;
        }
        List<ConstructorNode> declaredConstructors = cn.getDeclaredConstructors();
        if (declaredConstructors != null && !declaredConstructors.isEmpty()) {
            addError(ANNOTATION_NAME + " requires a class without constructors but " + cn.getName() + " has " + declaredConstructors.size(), node);
            return;
        }
    }

    private InnerClassNode createInnerClass(String tagName) {
        InnerClassNode inner = new InnerClassNode(
                classNode,
                classNode.getName() + "$" + StringGroovyMethods.capitalize(tagName) + "Tag",
                ACC_STATIC | ACC_PRIVATE,
                ABSTRACT_TAG
        );
        addConstructor(inner);
        sourceUnit.getAST().addClass(inner);
        return inner;
    }

    private void createTextMethodsForTag(ClassNode cn, String tagName) {
        Statement body = new ExpressionStatement(
                new MethodCallExpression(
                        new VariableExpression("this"),
                        tagName,
                        new ConstantExpression("")
                )
        );
        cn.addMethod(tagName, ACC_PUBLIC, ClassHelper.VOID_TYPE, Parameter.EMPTY_ARRAY, ClassNode.EMPTY_ARRAY, body);
        Parameter param = new Parameter(ClassHelper.STRING_TYPE, "text");

        BinaryExpression pre = createWriteOperation(cn, new ConstantExpression("<"+tagName+">"));
        BinaryExpression text = createWriteOperation(cn, new VariableExpression(param));
        BinaryExpression post = createWriteOperation(cn, new ConstantExpression("</"+tagName+">"));
        BlockStatement code = new BlockStatement();
        code.addStatement(new ExpressionStatement(pre));
        code.addStatement(new ExpressionStatement(text));
        code.addStatement(new ExpressionStatement(post));
        cn.addMethod(tagName, ACC_PUBLIC, ClassHelper.VOID_TYPE, new Parameter[]{param}, ClassNode.EMPTY_ARRAY,code);

    }

    private BinaryExpression createWriteOperation(final ClassNode cn, final Expression expr) {
        return new BinaryExpression(
                    new VariableExpression(cn.getField("out")),
                    Token.newSymbol(Types.LEFT_SHIFT, -1, -1),
                    expr
            );
    }

    private void createMethodForTag(ClassNode cn, String tagName, ClassNode tag, final Options options) {
        Parameter closureArg = new Parameter(ClassHelper.CLOSURE_TYPE.getPlainNodeReference(), "code");
        AnnotationNode dtAnn = new AnnotationNode(ClassHelper.make(DelegatesTo.class));
        dtAnn.addMember("value", new ClassExpression(tag));
        closureArg.addAnnotation(dtAnn);
        Parameter[] params = new Parameter[]{
                closureArg
        };
        MethodCallExpression call = new MethodCallExpression(
                new VariableExpression("this"),
                "delegateToTag",
                new ArgumentListExpression(
                        new ClassExpression(tag),
                        new VariableExpression(closureArg),
                        new ConstantExpression(null))
        );
        cn.addMethod(new MethodNode(tagName, ACC_PUBLIC, ClassHelper.VOID_TYPE, params, ClassNode.EMPTY_ARRAY, new ExpressionStatement(call)));
        if (options.attributes!=null) {
            // add another method taking a map of attributes
            closureArg = new Parameter(ClassHelper.CLOSURE_TYPE.getPlainNodeReference(), "code");
            dtAnn = new AnnotationNode(ClassHelper.make(DelegatesTo.class));
            dtAnn.addMember("value", new ClassExpression(tag));
            closureArg.addAnnotation(dtAnn);
            Parameter attributes = new Parameter(ATTRIBUTE_MAP_TYPE, "attributes");
            params = new Parameter[]{
                    attributes,
                    closureArg
            };
            call = new MethodCallExpression(
                    new VariableExpression("this"),
                    "delegateToTag",
                    new ArgumentListExpression(
                            new ClassExpression(tag),
                            new VariableExpression(closureArg),
                            new VariableExpression(attributes))
            );
            cn.addMethod(new MethodNode(tagName, ACC_PUBLIC, ClassHelper.VOID_TYPE, params, ClassNode.EMPTY_ARRAY, new ExpressionStatement(call)));
        }
    }

    private void translateSchema(ClassNode cn, ClosureExpression cle) {
        Statement code = cle.getCode();
        translateSchema(cn, code);
    }

    private void translateSchema(final ClassNode cn, final Statement code) {
        if (code instanceof BlockStatement) {
            for (Statement sub : ((BlockStatement) code).getStatements()) {
                translateSchema(cn, sub);
            }
        } else if (code instanceof ExpressionStatement) {
            ExpressionStatement es = (ExpressionStatement) code;
            Expression expression = es.getExpression();
            if (expression instanceof MethodCallExpression) {
                translateSchema(cn, (MethodCallExpression)expression);
            } else {
                addError("Unsupported schema node", expression);
            }
        } else {
            addError("Unsupported schema node", code);
        }
    }

    private void translateSchema(final ClassNode cn, final MethodCallExpression expression) {
        String tagName = expression.getMethodAsString();
        if (!expression.isImplicitThis()) {
            addError("Invalid schema node", expression);
            return;
        }
        InnerClassNode tag = tagNameToClass.get(tagName);
        if (tag==null) {
            tag = createInnerClass(tagName);
            tagNameToClass.put(tagName, tag);
        }
        ClosureExpression schema = null;
        Options options = DEFAULT_OPTIONS;
        Expression arguments = expression.getArguments();
        if (arguments instanceof ArgumentListExpression) {
            ArgumentListExpression listExpression = (ArgumentListExpression) arguments;
            List<Expression> expressions = listExpression.getExpressions();
            int size = expressions.size();
            if (!expressions.isEmpty()) {
                Expression lastExpr = listExpression.getExpression(size -1);
                if (lastExpr instanceof ClosureExpression) {
                    schema = (ClosureExpression) lastExpr;
                }
                Expression optionsExpression = listExpression.getExpression(0);
                if (optionsExpression instanceof MapExpression) {
                    options = Options.fromMapExpression((MapExpression) optionsExpression);
                }
            }
        }
        if (options.allowText) {
            createTextMethodsForTag(cn, tagName);
        }
        createMethodForTag(cn, tagName, tag, options);
        if (schema!=null) {
            translateSchema(tag, schema);
        }
    }

    private final static class Options {
        final boolean allowText;
        final Set<String> attributes;

        static Options fromMapExpression(MapExpression map) {
            boolean allowText = DEFAULT_OPTIONS.allowText;
            Set<String> attributes = new HashSet<String>();
            List<MapEntryExpression> mapEntryExpressions = map.getMapEntryExpressions();
            for (MapEntryExpression mapEntryExpression : mapEntryExpressions) {
                String key = mapEntryExpression.getKeyExpression().getText();
                Expression valueExpression = mapEntryExpression.getValueExpression();
                if ("allowText".equals(key)) {
                    allowText = Boolean.valueOf(valueExpression.getText());
                }
                if ("attributes".equals(key) && valueExpression instanceof ListExpression) {
                    for (Expression attr : ((ListExpression) valueExpression).getExpressions()) {
                        attributes.add(attr.getText());
                    }
                }
            }
            return new Options(allowText, attributes);
        }

        private Options(boolean allowText, Set<String> attributes) {
            this.allowText = allowText;
            this.attributes = Collections.unmodifiableSet(attributes);
        }
    }
}
