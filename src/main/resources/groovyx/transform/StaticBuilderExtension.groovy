import groovyx.runtime.AbstractTag
import groovyx.compiler.StaticMarkupBuilderASTTransformation.CheckAttributes as CA

def tagNode = classNodeFor(AbstractTag)
def attrNode = classNodeFor(CA)

def buildAllowedAnnotations(expr) {
    Set<String> result = []
    if (isListExpression(expr)) {
        expr.expressions.each { result << it.text }
    } else {
        result << expr.text
    }
    result
}

onMethodSelection { expr, methodNode ->
    if (methodNode.declaringClass.isDerivedFrom(tagNode)) {
        methodNode.parameters.each {
            if (isAnnotatedBy(it, attrNode)) {
                // found the map parameter
                def constraint = it.getAnnotations(attrNode)[0].getMember('value')
                def allowedParams = buildAllowedAnnotations(constraint)
                def args = getArguments(expr)
                def map = args[0] // we're sure it exists because it's the selected method
                map.mapEntryExpressions.each {
                    if (!allowedParams.contains(it.keyExpression.text)) {
                        addStaticTypeError("Attribute ${it.keyExpression.text} is not allowed here", it)
                    }
                }
            }
        }
    }
}