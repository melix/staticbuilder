package groovyx.runtime

import groovy.transform.CompileStatic

@CompileStatic
abstract class AbstractTag {
    private final static Set<String> DEFAULT_ALLOWED_ATTRIBUTES = Collections.emptySet()

    protected final OutputStream out
    private final String tagName
    private final Set<String> allowedAttributes;

    AbstractTag(OutputStream out) {
        this.out = out
        def sn = getClass().simpleName
        int idx = sn.lastIndexOf('Tag')
        this.tagName = idx>0?sn.substring(sn.lastIndexOf('$')+1, idx).toLowerCase():''
        this.allowedAttributes = DEFAULT_ALLOWED_ATTRIBUTES
    }

    void render(Closure body, Map attrs = null) {
        // todo: escape value properly
        def attrAsString = attrs?" "+attrs.collect { Map.Entry it -> "$it.key='$it.value'"}.join(' '):''
        out << "<${tagName}${attrAsString}>"
        body()
        out << "</$tagName>"
    }

    public void delegateToTag(Class clazz, Closure body, Map attrs) {
        AbstractTag tag = (AbstractTag) clazz.newInstance(out)
        def clone = body.rehydrate(tag, this, this)
        tag.render(clone, attrs)
    }

}