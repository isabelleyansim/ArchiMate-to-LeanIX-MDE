import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Converts LeanIX XMI into CSV files.
 * 
 * Outputs:
 *  - fact_sheets.csv
 *  - relationships.csv
 *  - trace_links.csv
 */

public class LeanIXXMIToCSVConverter {

    private static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";

    private static final String[] FACT_COLUMNS = {
        "id", "externalId", "ecoreClass", "name", "description",
        "factSheetTypeKey", "factSheetTypeLabel", "factSheetSubtypeKey",
        "factSheetSubtypeLabel", "isCustomType", "dataFlowDirection",
        "mappingRuleId", "traceIds"
    };
    private static final String[] REL_COLUMNS = {
        "id", "externalId", "source", "sourceName", "sourceTypeKey",
        "sourceEClass", "relationTypeKey", "relationTypeLabel",
        "reverseRelationTypeLabel", "target", "targetName",
        "targetTypeKey", "targetEClass", "isCustomRelation",
        "sourceRelationName", "mappingRuleId", "description", "traceIds"
    };
    private static final String[] TRACE_COLUMNS = {
        "traceId", "ruleId", "kind", "targetArtifactId", "targetArtifactType",
        "targetReferenceClass", "sourceArtifactIds", "sourceArtifactTypes",
        "sourceArtifactCount"
    };
    public static void main(String[] args) {
        try {
            if (args.length != 0 && args.length != 3 && args.length != 4) {
                throw new IllegalArgumentException("Use 0, 3 or 4 arguments: "
                    + "<leanix.ecore> <result.xmi> <output-directory> [trace.xmi]");
            }

            File ecore = new File(args.length == 0 ? "model/leanix.ecore" : args[0]);
            File result = new File(args.length == 0 ? "model/archisurance-evaluation_result.xmi" : args[1]);
            File out = new File(args.length == 0 ? "output/parsed" : args[2]);
            File trace = args.length == 4 ? new File(args[3])
                    : (args.length == 0 ? new File("model/archisurance-evaluation_trace.xmi") : null);

            requireFile(ecore, "LeanIX Ecore");
            requireFile(result, "LeanIX result XMI");
            if (trace != null && !trace.exists() && args.length == 4) {
                throw new IOException("Trace XMI not found: " + trace);
            }

            
            Document metamodel = readXml(ecore);
            Element pkg = metamodel.getDocumentElement();
            if (!"EPackage".equals(localName(pkg))) {
                throw new IOException("Ecore root is not EPackage");
            }
            String targetNamespace = pkg.getAttribute("nsURI");
            Set<String> classifiers = new LinkedHashSet<>();
            for (Element classifier : children(pkg, "eClassifiers")) {
                classifiers.add(classifier.getAttribute("name"));
            }
            for (String necessary : Arrays.asList("LeanIXModel", "FactSheet", "FactSheetRelation")) {
                if (!classifiers.contains(necessary)) {
                    throw new IOException("Missing expected Ecore classifier: " + necessary);
                }
            }

            Document doc = readXml(result);
            Element model = doc.getDocumentElement();
            if (!"LeanIXModel".equals(localName(model))
                    || !targetNamespace.equals(model.getNamespaceURI())) {
                throw new IOException("XMI root does not match leanix.ecore ("
                    + targetNamespace + ")");
            }

            List<Map<String, String>> facts = new ArrayList<>();
            Map<String, Map<String, String>> byId = new LinkedHashMap<>();
            for (Element el : children(model, "factSheets")) {
                Map<String, String> f = attributes(el, FACT_COLUMNS);
                String eClass = simpleType(el.getAttributeNS(XSI_NS, "type"));
                if (eClass.isEmpty()) {
                    throw new IOException("Fact Sheet without xsi:type: " + el.getAttribute("id"));
                }
                if (!classifiers.contains(eClass)) {
                    throw new IOException("Unknown Ecore Fact Sheet class: " + eClass);
                }
                f.put("ecoreClass", eClass);
                
                f.put("isCustomType", Boolean.toString(Boolean.parseBoolean(f.get("isCustomType"))));
                String id = required(f, "id", "Fact Sheet");
                if (byId.putIfAbsent(id, f) != null) {
                    throw new IOException("Duplicate Fact Sheet ID: " + id);
                }
                facts.add(f);
            }

            List<Map<String, String>> relations = new ArrayList<>();
            Set<String> relationIds = new LinkedHashSet<>();
            for (Element el : children(model, "relations")) {
                Map<String, String> r = attributes(el, REL_COLUMNS);

                r.put("isCustomRelation", Boolean.toString(Boolean.parseBoolean(r.get("isCustomRelation"))));
                String id = required(r, "id", "Relationship");
                if (!relationIds.add(id)) {
                    throw new IOException("Duplicate relationship ID: " + id);
                }
                Map<String, String> source = byId.get(required(r, "source", "Relationship " + id));
                Map<String, String> target = byId.get(required(r, "target", "Relationship " + id));
                if (source == null || target == null) {
                    throw new IOException("Unresolved relationship endpoints for " + id
                        + ": source=" + r.get("source") + ", target=" + r.get("target"));
                }
                r.put("sourceName", source.get("name"));
                r.put("sourceTypeKey", source.get("factSheetTypeKey"));
                r.put("sourceEClass", source.get("ecoreClass"));
                r.put("targetName", target.get("name"));
                r.put("targetTypeKey", target.get("factSheetTypeKey"));
                r.put("targetEClass", target.get("ecoreClass"));
                relations.add(r);
            }

            List<Map<String, String>> traces = new ArrayList<>();
            Map<String, List<String>> traceIdsByTarget = new LinkedHashMap<>();
            if (trace != null && trace.isFile()) {
                Document traceDoc = readXml(trace);
                Element traceRoot = traceDoc.getDocumentElement();
                if (!"TraceModel".equals(localName(traceRoot))) {
                    throw new IOException("Trace XMI root is not TraceModel");
                }
                for (Element traceEl : children(traceRoot, "traces")) {
                    Map<String, String> t = new LinkedHashMap<>();
                    t.put("traceId", traceEl.getAttribute("id"));
                    t.put("ruleId", traceEl.getAttribute("ruleId"));
                    // In trace.ecore, DIRECT is the default (enum value 0).
                    String kind = traceEl.getAttribute("kind");
                    t.put("kind", kind.isEmpty() ? "DIRECT" : kind);
                    List<Element> targetElements = children(traceEl, "targetArtifact");
                    if (targetElements.isEmpty()) {
                        throw new IOException("Trace without targetArtifact: " + t.get("traceId"));
                    }
                    Element target = targetElements.get(0);
                    String targetId = target.getAttribute("artifactId");
                    t.put("targetArtifactId", targetId);
                    t.put("targetArtifactType", target.getAttribute("artifactType"));
                    t.put("targetReferenceClass", simpleType(target.getAttributeNS(XSI_NS, "type")));
                    if (!byId.containsKey(targetId) && !relationIds.contains(targetId)) {
                        throw new IOException("Trace targets unknown generated object: " + targetId);
                    }
                    List<String> sourceIds = new ArrayList<>();
                    List<String> sourceTypes = new ArrayList<>();
                    for (Element source : children(traceEl, "sourceArtifacts")) {
                        sourceIds.add(source.getAttribute("artifactId"));
                        sourceTypes.add(source.getAttribute("artifactType"));
                    }
                    t.put("sourceArtifactIds", String.join(" | ", sourceIds));
                    t.put("sourceArtifactTypes", String.join(" | ", sourceTypes));
                    t.put("sourceArtifactCount", Integer.toString(sourceIds.size()));
                    traces.add(t);
                    traceIdsByTarget.computeIfAbsent(targetId, k -> new ArrayList<>())
                                    .add(t.get("traceId"));
                }
            }
            for (Map<String, String> f : facts) {
                f.put("traceIds", String.join(" | ",
                    traceIdsByTarget.getOrDefault(f.get("id"), Collections.emptyList())));
            }
            for (Map<String, String> r : relations) {
                r.put("traceIds", String.join(" | ",
                    traceIdsByTarget.getOrDefault(r.get("id"), Collections.emptyList())));
            }

            Files.createDirectories(out.toPath());
            
            writeCsv(out.toPath().resolve("fact_sheets.csv"), FACT_COLUMNS, facts);
            writeCsv(out.toPath().resolve("relationships.csv"), REL_COLUMNS, relations);

            if (trace != null && trace.isFile()) {
                writeCsv(out.toPath().resolve("trace_links.csv"), TRACE_COLUMNS, traces);
            } else {
                Files.deleteIfExists(out.toPath().resolve("trace_links.csv"));
            }

            System.out.println("Parsed LeanIX XMI successfully.");
            System.out.println("Fact Sheets: " + facts.size());
            System.out.println("Relationships: " + relations.size());
            System.out.println("Trace links: " + traces.size() + (trace == null || !trace.isFile() ? " (trace file not provided)" : ""));
            System.out.println("Output: " + out.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("Parsing failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void requireFile(File file, String label) throws IOException {
        if (!file.isFile()) {
            throw new IOException(label + " not found: " + file.getAbsolutePath());
        }
    }

    private static Document readXml(File file) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return f.newDocumentBuilder().parse(file);
    }

    private static String localName(Element el) {
        return el.getLocalName() != null ? el.getLocalName() : el.getTagName();
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> list = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (n instanceof Element && name.equals(localName((Element) n))) {
                list.add((Element) n);
            }
        }
        return list;
    }

    private static String simpleType(String qualified) {
        if (qualified == null) return "";
        int colon = qualified.lastIndexOf(':');
        return colon >= 0 ? qualified.substring(colon + 1) : qualified;
    }

    private static Map<String, String> attributes(Element el, String[] columns) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String col : columns) {
            result.put(col, el.getAttribute(col));
        }
        return result;
    }

    private static String required(Map<String, String> obj, String col, String label)
            throws IOException {
        String value = obj.get(col);
        if (value == null || value.isEmpty()) {
            throw new IOException(label + " missing " + col);
        }
        return value;
    }

    private static void writeCsv(Path path, String[] columns, List<Map<String, String>> rows)
            throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write(String.join(",", columns));
            writer.write("\n");
            for (Map<String, String> row : rows) {
                for (int i = 0; i < columns.length; i++) {
                    if (i > 0) writer.write(',');
                    writer.write(csv(row.getOrDefault(columns[i], "")));
                }
                writer.write("\n");
            }
        }
    }

    private static String csv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
