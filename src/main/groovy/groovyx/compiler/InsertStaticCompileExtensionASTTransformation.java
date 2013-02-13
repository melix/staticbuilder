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

import groovy.transform.CompileStatic;
import groovy.transform.TypeChecked;
import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.CastExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.ListExpression;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.transform.AbstractASTTransformation;
import org.codehaus.groovy.transform.GroovyASTTransformation;

import java.util.List;

@GroovyASTTransformation(phase = CompilePhase.SEMANTIC_ANALYSIS)
public class InsertStaticCompileExtensionASTTransformation extends AbstractASTTransformation {
    private final static ClassNode TYPECHECKED_CN = ClassHelper.make(TypeChecked.class);
    private final static ClassNode COMPILESTATIC_CN = ClassHelper.make(CompileStatic.class);
    private final static String EXTENSION_CLASSPATH = "groovyx/transform/StaticBuilderExtension.groovy";

    @Override
    public void visit(final ASTNode[] nodes, final SourceUnit source) {
        ASTNode firstNode = nodes[0];
        if (firstNode instanceof ModuleNode) {
            ModuleNode module = (ModuleNode) firstNode;
            List<ClassNode> classes = module.getClasses();
            ClassCodeVisitorSupport visitor = new ClassCodeVisitorSupport() {
                @Override
                protected SourceUnit getSourceUnit() {
                    return source;
                }

                @Override
                public void visitAnnotations(final AnnotatedNode node) {
                    super.visitAnnotations(node);
                    List<AnnotationNode> annotations = node.getAnnotations();
                    for (AnnotationNode ann : annotations) {
                        ClassNode annCN = ann.getClassNode();
                        if (annCN.equals(TYPECHECKED_CN) || annCN.equals(COMPILESTATIC_CN)) {
                            updateAnnotation(ann);
                        }

                    }
                }
            };
            for (ClassNode classNode : classes) {
                visitor.visitClass(classNode);
            }
        }
    }


    private void updateAnnotation(final AnnotationNode ann) {
        Expression value = ann.getMember("value");
        if (value==null || !value.getText().contains("SKIP")) {
            Expression extensions = ann.getMember("extensions");
            ConstantExpression extensionClasspath = new ConstantExpression(EXTENSION_CLASSPATH);
            if (extensions==null) {
                // damn so easy!
                ann.addMember("extensions", extensionClasspath);
            } else {
                if (extensions instanceof ConstantExpression) {
                    // wrap it into a list
                    ListExpression list = new ListExpression();
                    list.addExpression(extensions);
                    list.addExpression(extensionClasspath);
                    ann.setMember("extensions", list);
                } else if (extensions instanceof ListExpression) {
                    // add element into the list
                    ((ListExpression) extensions).addExpression(extensionClasspath);
                }
            }
        }
    }
}
