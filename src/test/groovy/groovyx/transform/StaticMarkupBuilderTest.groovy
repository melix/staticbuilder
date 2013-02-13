package groovyx.transform

import groovy.transform.CompileStatic
import groovyx.runtime.AbstractTag

class StaticMarkupBuilderTest extends GroovyTestCase {
    void testSimpleBuilder() {
        def out = new ByteArrayOutputStream()
        def builder = new SimpleBuilder(out)
        assert builder instanceof AbstractTag
    }

    @CompileStatic
    void testBuilderWithSingleTag() {
        def out = new ByteArrayOutputStream()
        def builder = new Builder1(out)
        assert builder instanceof AbstractTag
        builder.html { }
        assert out.toString() == '<html></html>'
    }

    @CompileStatic
    void testBuilderWithEmbeddedTags() {
        def out = new ByteArrayOutputStream()
        def builder = new Builder2(out)
        assert builder instanceof AbstractTag
        builder.html {
            head { title() }
            body {
                p 'Hello'
            }
        }
        assert out.toString() == '<html><head><title></title></head><body><p>Hello</p></body></html>'
    }

    @CompileStatic
    void testBuilderWithTagAndAttributes() {
        def out = new ByteArrayOutputStream()
        def builder = new Builder2(out)
        assert builder instanceof AbstractTag
        builder.html {
            body {
                out << 'Hello '
                a(href:'http://groovy.codehaus.org') { out << 'Groovy'}
            }
        }
        assert out.toString() == "<html><body>Hello <a href='http://groovy.codehaus.org'>Groovy</a></body></html>"
    }

    @StaticMarkupBuilder
    private static class SimpleBuilder {
    }

    @StaticMarkupBuilder
    private static class Builder1 {
       static schema = {
           html()
       }
    }

    @StaticMarkupBuilder
    private static class Builder2 {
       static schema = {
           html {
               head {
                   title()
               }
               body(allowText:true) {
                   p(allowText: true)
                   a(allowText: true, attributes:['href', 'target'])
               }
           }
       }
    }
}
