import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

/**
 * Conformance gate for the odds feed schema.
 *
 * <p>Two checks, both of which have to hold for the repository to describe the
 * live contract:
 *
 * <ol>
 *   <li>every top level schema under schema/feed and schema/rest compiles - the
 *       SDKs generate their bindings from these files, so a schema that does not
 *       compile is a schema nobody can consume;
 *   <li>every captured payload under test/fixtures/{feed,rest}/&lt;name&gt;/*.xml
 *       validates against schema/{feed,rest}/&lt;name&gt;.xsd. No type in the
 *       schema declares xs:anyAttribute, so an attribute a producer starts
 *       sending without declaring it here fails this step.
 * </ol>
 *
 * <p>Usage: {@code java SchemaCheck <repository-root>}
 */
public final class SchemaCheck {

    private static final String XSD_NS = XMLConstants.W3C_XML_SCHEMA_NS_URI;

    /** Directories holding schema files, each mirrored by test/fixtures/&lt;section&gt;. */
    private static final String[] SECTIONS = {"common", "feed", "rest"};

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();

        List<String> failures = new ArrayList<>();
        int schemas = 0;
        int fixtures = 0;

        for (String section : SECTIONS) {
            for (Path xsd : listXsds(root.resolve("schema").resolve(section))) {
                schemas++;
                String name = fileName(xsd).replaceFirst("\\.xsd$", "");

                Schema schema;
                try {
                    schema = compile(xsd);
                } catch (SAXParseException e) {
                    failures.add(rel(root, xsd) + ": does not compile: " + describe(e));
                    continue;
                }
                System.out.println("schema  ok  " + rel(root, xsd));

                // A schema with no global element is a shared type library
                // (schema/common, schema/feed/types.xsd): nothing can be an
                // instance of it, so there is nothing to validate against it.
                if (!declaresGlobalElement(xsd)) {
                    continue;
                }

                Path fixtureDir = root.resolve("test/fixtures").resolve(section).resolve(name);
                List<Path> payloads = listXmls(fixtureDir);
                if (payloads.isEmpty()) {
                    failures.add(rel(root, xsd) + ": no payload in " + rel(root, fixtureDir)
                            + " - every message type and endpoint needs at least one");
                    continue;
                }
                for (Path payload : payloads) {
                    fixtures++;
                    List<String> errors = validate(schema, payload);
                    if (errors.isEmpty()) {
                        System.out.println("payload ok  " + rel(root, payload));
                    } else {
                        for (String error : errors) {
                            failures.add(rel(root, payload) + ": " + error);
                        }
                    }
                }
            }
        }

        // Payloads whose schema was renamed or removed would otherwise never run.
        for (String section : SECTIONS) {
            Path fixtureRoot = root.resolve("test/fixtures").resolve(section);
            if (!Files.isDirectory(fixtureRoot)) {
                continue;
            }
            try (Stream<Path> dirs = Files.list(fixtureRoot)) {
                for (Path dir : dirs.filter(Files::isDirectory).sorted().toArray(Path[]::new)) {
                    Path xsd = root.resolve("schema").resolve(section)
                            .resolve(fileName(dir) + ".xsd");
                    if (!Files.isRegularFile(xsd)) {
                        failures.add(rel(root, dir) + ": no matching " + rel(root, xsd));
                    }
                }
            }
        }

        System.out.println();
        System.out.printf("%d schemas, %d payloads, %d problems%n",
                schemas, fixtures, failures.size());
        if (!failures.isEmpty()) {
            System.out.println();
            for (String failure : failures) {
                System.out.println("FAIL " + failure);
            }
            System.exit(1);
        }
    }

    private static boolean declaresGlobalElement(Path xsd) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().parse(xsd.toFile());
        NodeList children = document.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && XSD_NS.equals(child.getNamespaceURI())
                    && "element".equals(child.getLocalName())) {
                return true;
            }
        }
        return false;
    }

    private static Schema compile(Path xsd) throws Exception {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.setErrorHandler(new FailFast());
        return factory.newSchema(new StreamSource(xsd.toFile()));
    }

    private static List<String> validate(Schema schema, Path payload) throws Exception {
        Collect collect = new Collect();
        Validator validator = schema.newValidator();
        validator.setErrorHandler(collect);
        try {
            validator.validate(new StreamSource(payload.toFile()));
        } catch (SAXParseException e) {
            collect.messages.add(describe(e));
        }
        return collect.messages;
    }

    private static List<Path> listXsds(Path dir) throws IOException {
        return list(dir, ".xsd");
    }

    private static List<Path> listXmls(Path dir) throws IOException {
        return list(dir, ".xml");
    }

    private static List<Path> list(Path dir, String suffix) throws IOException {
        if (!Files.isDirectory(dir)) {
            return new ArrayList<>();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            List<Path> found = new ArrayList<>();
            entries.filter(Files::isRegularFile)
                    .filter(p -> fileName(p).endsWith(suffix))
                    .sorted(Comparator.comparing(SchemaCheck::fileName))
                    .forEach(found::add);
            return found;
        }
    }

    private static String describe(SAXParseException e) {
        return "line " + e.getLineNumber() + ": " + e.getMessage();
    }

    private static String fileName(Path path) {
        return path.getFileName().toString();
    }

    private static String rel(Path root, Path path) {
        return root.relativize(path).toString();
    }

    /** Turns schema compilation warnings into failures too - a warning here is drift. */
    private static final class FailFast implements ErrorHandler {
        public void warning(SAXParseException e) throws SAXParseException {
            throw e;
        }

        public void error(SAXParseException e) throws SAXParseException {
            throw e;
        }

        public void fatalError(SAXParseException e) throws SAXParseException {
            throw e;
        }
    }

    /** Reports every problem in a payload rather than stopping at the first. */
    private static final class Collect implements ErrorHandler {
        private final List<String> messages = new ArrayList<>();

        public void warning(SAXParseException e) {
            messages.add(describe(e));
        }

        public void error(SAXParseException e) {
            messages.add(describe(e));
        }

        public void fatalError(SAXParseException e) {
            messages.add(describe(e));
        }
    }
}
