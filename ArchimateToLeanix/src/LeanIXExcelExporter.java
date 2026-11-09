import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/**
 * Creates Excel files for importing Fact Sheets and relationships into LeanIX.
 * Stages: create, relations-hierarchy, relations.
 * Arguments: <stage> <parsedCsvDir> <leanixExcelDir> <outputDir>
 */

public class LeanIXExcelExporter {
    private static final String TAG_COLUMN = "tags:thesis";
    private static final String TAG = "archisurance";

    private static final Map<String, String> TYPES = new LinkedHashMap<>();
    static {
        TYPES.put("application", "Application");
        TYPES.put("businessCapability", "BusinessCapability");
        TYPES.put("businessContext", "BusinessContext");
        TYPES.put("dataObject", "DataObject");
        TYPES.put("interface", "Interface");
        TYPES.put("itComponent", "ITComponent");
        TYPES.put("initiative", "Initiative");
        TYPES.put("objective", "Objective");
        TYPES.put("organization", "Organization");
        TYPES.put("service", "Service");
    }

    private static final class Inventory {
        final List<String> columns = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        final List<Map<String, String>> rows = new ArrayList<>();

        boolean has(String name) { return columns.contains(name); }
        String label(String name) {
            int index = columns.indexOf(name);
            return index >= 0 && index < labels.size() && !labels.get(index).isBlank()
                    ? labels.get(index) : name;
        }
        List<Map<String, String>> find(String name) {
            List<Map<String, String>> matches = new ArrayList<>();
            for (Map<String, String> row : rows) {
                if (!value(row, "name").equals(name)) continue;
                for (String assigned : value(row, TAG_COLUMN).split(";")) {
                    if (TAG.equals(assigned.trim())) {
                        matches.add(row);
                        break;
                    }
                }
            }
            return matches;
        }
    }

    private static final class Workbook {
        final String filename;
        final List<String> columns;
        final List<String> labels;
        final List<List<String>> rows;

        Workbook(String filename, List<String> columns, List<String> labels,
                 List<List<String>> rows) {
            this.filename = filename;
            this.columns = columns;
            this.labels = labels;
            this.rows = rows;
        }
    }

    public static void main(String[] args) {
        try {
            if (args.length != 0 && args.length != 4) {
                throw new IllegalArgumentException(
                        "Usage: <create|relations-hierarchy|relations> <parsedDir> <leanixDir> <outputDir>");
            }
            String stage = args.length == 0 ? "create" : args[0];
            if (!Arrays.asList("create", "relations-hierarchy", "relations").contains(stage)) {
                throw new IllegalArgumentException("Unknown stage: " + stage);
            }

            Path csvDir = Paths.get(args.length == 0 ? "output/parsed" : args[1]);
            Path leanixDir = Paths.get(args.length == 0 ? "model/leanix_templates" : args[2]);
            Path outputDir = Paths.get(args.length == 0 ? "output/excel" : args[3]);
            String prefix = stage.equals("create") ? "01_CREATE_"
                    : stage.equals("relations-hierarchy") ? "02_HIERARCHY_" : "03_RELATIONS_";

            Files.createDirectories(outputDir);
            removeOldStageFiles(outputDir, prefix);

            List<Map<String, String>> facts = readCsv(csvDir.resolve("fact_sheets.csv"));
            requireColumns(facts, "id", "name", "factSheetTypeKey", "factSheetSubtypeKey");
            Map<String, Map<String, String>> byId = indexFacts(facts);
            List<Workbook> workbooks;

            if (stage.equals("create")) {
                workbooks = createFactSheets(facts, leanixDir);
            } else {
                List<Map<String, String>> relations = readCsv(csvDir.resolve("relationships.csv"));
                requireColumns(relations, "id", "source", "target", "relationTypeKey");
                for (Map<String, String> relation : relations) {
                    if (!byId.containsKey(value(relation, "source")) ||
                        !byId.containsKey(value(relation, "target"))) {
                        throw new IOException("Unknown endpoint in relationship " + value(relation, "id"));
                    }
                }
                workbooks = createRelationships(facts, relations, byId, leanixDir,
                        stage.equals("relations-hierarchy"));
            }

            saveWorkbooks(outputDir, prefix, workbooks);
            System.out.println("Created " + workbooks.size() + " Excel file(s).");
        } catch (Exception ex) {
            System.err.println("Export failed: " + ex.getMessage());
            System.exit(1);
        }
    }

    private static Map<String, Map<String, String>> indexFacts(List<Map<String, String>> facts)
            throws IOException {
        Map<String, Map<String, String>> index = new LinkedHashMap<>();
        for (Map<String, String> fact : facts) {
            String id = value(fact, "id");
            if (id.isBlank() || index.putIfAbsent(id, fact) != null) {
                throw new IOException("Empty or duplicate Fact Sheet ID: " + id);
            }
        }
        return index;
    }

    private static String relationColumn(String sourceType, String relation, String targetType) {
        if ("parentChild".equals(relation) && sourceType.equals(targetType)) return "relToChild";
        switch (sourceType + "|" + relation + "|" + targetType) {
            case "dataObject|aggregation|dataObject": return "relAggregatedDataObjectToAggregatesDataObject";
            case "application|applicationProvidesInterface|interface": return "relProviderApplicationToInterface";
            case "application|applicationConsumesInterface|interface": return "relConsumerApplicationToInterface";
            case "application|applicationRealizesService|service": return "relApplicationToRealizesService";
            case "application|applicationServedByService|service": return "relServesApplicationToService";
            case "application|applicationAccessesData|dataObject": return "relApplicationToDataObject";
            case "application|applicationServesBusinessContext|businessContext": return "relApplicationToBusinessContext";
            case "application|applicationServesOrganization|organization": return "relApplicationToOrganization";
            case "application|applicationSupportsBusinessCapability|businessCapability": return "relApplicationToBusinessCapability";
            case "dataObject|dataObjectRealizesBusinessObject|dataObject": return "relRealizedDataObjectToRealizesDataObject";
            case "itComponent|itComponentDeploysData|dataObject": return "relITComponentToDeploysDataObject";
            case "itComponent|itComponentRealizesService|service": return "relITComponentToRealizesService";
            case "itComponent|itComponentServesApplication|application": return "relITComponentToApplication";
            case "organization|organizationAssignedToBusinessContext|businessContext": return "relOrganizationToAssignedToBusinessContext";
            case "service|serviceServesApplication|application": return "relServiceToServesApplication";
            case "service|businessServiceServesOrganization|organization": return "relServiceToServesOrganization";
            case "service|serviceServesBusinessContext|businessContext": return "relServiceToServesBusinessContext";
            default: return "";
        }
    }

    private static List<Workbook> createFactSheets(List<Map<String, String>> facts,
                                                    Path templateDir) throws Exception {
        Map<String, List<Map<String, String>>> groups = new TreeMap<>();
        for (Map<String, String> fact : facts) {
            groups.computeIfAbsent(value(fact, "factSheetTypeKey"), k -> new ArrayList<>()).add(fact);
        }
        List<Workbook> result = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, String>>> group : groups.entrySet()) {
            String type = requireType(group.getKey());
            Inventory template = readExcel(templateDir.resolve(type + ".xlsx"));
            requireFields(template, "id", "type", "name", "description", TAG_COLUMN);

            boolean hasSubtype = group.getValue().stream()
                    .anyMatch(f -> !value(f, "factSheetSubtypeKey").isBlank());
            if (hasSubtype) requireFields(template, "category");

            boolean hasDirection = type.equals("Interface");
            if (hasDirection) requireFields(template, "dataFlowDirection");

            Set<String> existing = new HashSet<>();
            for (Map<String, String> row : template.rows) {
                if (!value(row, "type").isBlank() && !type.equals(value(row, "type"))) {
                    throw new IOException("Wrong Fact Sheet type in template " + type + ".xlsx");
                }
                if (!value(row, "id").isBlank()) existing.add(value(row, "name"));
            }
            Set<String> names = new HashSet<>();
            List<String> columns = new ArrayList<>(Arrays.asList("id", "type", "name", "description"));
            if (hasSubtype) columns.add("category");
            if (hasDirection) columns.add("dataFlowDirection");
            columns.add(TAG_COLUMN);
            List<List<String>> rows = new ArrayList<>();
            for (Map<String, String> fact : group.getValue()) {
                String name = value(fact, "name");
                if (name.isBlank() || invalidRelationText(name) || !names.add(name) || existing.contains(name)) {
                    throw new IOException("Invalid, duplicate or existing " + type + " name: " + name);
                }
                Map<String, String> record = new HashMap<>();
                record.put("id", "");
                record.put("type", type);
                record.put("name", name);
                record.put("description", value(fact, "description"));
                record.put("category", value(fact, "factSheetSubtypeKey"));
                if (hasDirection) {
                    record.put("dataFlowDirection", leanixDirection(value(fact, "dataFlowDirection")));
                }
                record.put(TAG_COLUMN, TAG);
                rows.add(values(record, columns));
            }
            result.add(new Workbook("01_CREATE_" + type + ".xlsx", columns, labels(template, columns), rows));
        }
        return result;
    }

    private static String leanixDirection(String direction) throws IOException {
        switch (direction.toUpperCase(Locale.ROOT)) {
            case "": return "";
            case "INCOMING": return "incoming";
            case "OUTGOING": return "outgoing";
            case "BI_DIRECTIONAL":
            case "BIDIRECTIONAL": return "biDirectional";
            default: throw new IOException("Unknown data flow direction: " + direction);
        }
    }

    private static List<Workbook> createRelationships(List<Map<String, String>> facts,
            List<Map<String, String>> relations, Map<String, Map<String, String>> byId,
            Path inventoryDir, boolean hierarchy) throws Exception {
        Map<String, Inventory> inventories = new HashMap<>();
        for (Map<String, String> fact : facts) {
            String typeKey = value(fact, "factSheetTypeKey");
            if (inventories.containsKey(typeKey)) continue;
            String type = requireType(typeKey);
            Path path = inventoryDir.resolve(type + ".xlsx");
            if (Files.isRegularFile(path)) {
                Inventory inventory = readExcel(path);
                requireFields(inventory, "id", "type", "name", "displayName", TAG_COLUMN);
                inventories.put(typeKey, inventory);
            }
        }

        Map<String, Set<String>> children = new HashMap<>();
        for (Map<String, String> rel : relations) {
            if ("parentChild".equals(value(rel, "relationTypeKey"))) {
                children.computeIfAbsent(value(rel, "source"), k -> new LinkedHashSet<>())
                        .add(value(rel, "target"));
            }
        }
        Set<String> redundant = new HashSet<>();
        Map<String, String> parent = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (Map<String, String> rel : relations) {
            if (!"parentChild".equals(value(rel, "relationTypeKey"))) continue;
            if (indirectPath(value(rel, "source"), value(rel, "target"), children)) {
                redundant.add(value(rel, "id"));
                continue;
            }
            String previous = parent.putIfAbsent(value(rel, "target"), value(rel, "source"));
            if (previous != null && !previous.equals(value(rel, "source"))) {
                ambiguous.add(value(rel, "target"));
            }
        }

        Map<String, Set<String>> semantics = new HashMap<>();
        for (Map<String, String> rel : relations) {
            String source = value(byId.get(value(rel, "source")), "factSheetTypeKey");
            String target = value(byId.get(value(rel, "target")), "factSheetTypeKey");
            String kind = value(rel, "relationTypeKey");
            String column = relationColumn(source, kind, target);
            if (!column.isBlank()) semantics.computeIfAbsent(source + "|" + target + "|" + column,
                    k -> new HashSet<>()).add(kind);
        }

        Map<String, Map<String, Map<String, LinkedHashSet<String>>>> groups = new TreeMap<>();
        Map<String, Map<String, String>> sourceRecords = new HashMap<>();
        for (Map<String, String> rel : relations) {
            String kind = value(rel, "relationTypeKey");
            if ("parentChild".equals(kind) != hierarchy) continue;
            String sourceId = value(rel, "source");
            String targetId = value(rel, "target");
            if (hierarchy && redundant.contains(value(rel, "id"))) {
                continue;
            }
            Map<String, String> source = byId.get(sourceId);
            Map<String, String> target = byId.get(targetId);
            String sourceType = value(source, "factSheetTypeKey");
            String targetType = value(target, "factSheetTypeKey");
            String column = relationColumn(sourceType, kind, targetType);
            String description = value(source, "name") + " -> " + value(target, "name") + " (" + kind + ")";
            if (column.isBlank()) throw new IOException("Unmapped LeanIX relation: " + description);
            if (semantics.getOrDefault(sourceType + "|" + targetType + "|" + column,
                    Collections.emptySet()).size() > 1) {
                throw new IOException("Conflicting LeanIX relation types for " + column + ": " + description);
            }
            if (hierarchy && (ambiguous.contains(targetId) || ambiguousAncestor(sourceId, parent, ambiguous))) {
                throw new IOException("Multiple parents or hierarchy cycle: " + description);
            }
            Inventory sourceInventory = inventories.get(sourceType);
            Inventory targetInventory = inventories.get(targetType);
            if (sourceInventory == null || targetInventory == null) {
                throw new IOException("Missing workspace inventory for: " + description);
            }
            requireFields(sourceInventory, column);
            Map<String, String> sourceRow = uniqueMatch(sourceInventory, value(source, "name"));
            Map<String, String> targetRow = uniqueMatch(targetInventory, value(target, "name"));
            String workspaceId = value(sourceRow, "id");
            String targetName = value(targetRow, "displayName");
            if (workspaceId.isBlank() || targetName.isBlank() || invalidRelationText(targetName)) {
                throw new IOException("Invalid workspace ID or display name: " + description);
            }
            String group = hierarchy ? sourceType + "::DEPTH_" + String.format("%03d", depth(sourceId, parent, new HashSet<>()))
                    : sourceType;
            String rowKey = sourceType + "\u001f" + workspaceId;
            Map<String, LinkedHashSet<String>> row = groups
                    .computeIfAbsent(group, k -> new LinkedHashMap<>())
                    .computeIfAbsent(rowKey, k -> new LinkedHashMap<>());
            LinkedHashSet<String> targets = row.computeIfAbsent(column, k -> new LinkedHashSet<>());
            for (String current : value(sourceRow, column).split(";")) {
                if (!current.isBlank()) targets.add(current.trim());
            }
            targets.add(targetName);
            sourceRecords.put(rowKey, sourceRow);
        }

        List<Workbook> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Map<String, LinkedHashSet<String>>>> entry : groups.entrySet()) {
            String groupKey = entry.getKey();
            String sourceType = groupKey.split("::", 2)[0];
            Inventory inventory = inventories.get(sourceType);
            Set<String> relationColumns = new HashSet<>();
            for (Map<String, LinkedHashSet<String>> row : entry.getValue().values()) {
                relationColumns.addAll(row.keySet());
            }
            List<String> columns = new ArrayList<>(Arrays.asList("id", "type", "name"));
            for (String col : inventory.columns) if (relationColumns.contains(col)) columns.add(col);
            List<List<String>> rows = new ArrayList<>();
            for (Map.Entry<String, Map<String, LinkedHashSet<String>>> record : entry.getValue().entrySet()) {
                List<String> cells = new ArrayList<>();
                Map<String, String> source = sourceRecords.get(record.getKey());
                for (String col : columns) {
                    cells.add(record.getValue().containsKey(col)
                            ? String.join(";", record.getValue().get(col)) : value(source, col));
                }
                rows.add(cells);
            }
            String suffix = groupKey.contains("::") ? "_" + groupKey.split("::", 2)[1] : "";
            String prefix = hierarchy ? "02_HIERARCHY_" : "03_RELATIONS_";
            result.add(new Workbook(prefix + requireType(sourceType) + suffix + ".xlsx",
                    columns, labels(inventory, columns), rows));
        }
        if (!hierarchy) {
            Workbook directions = updateInterfaceDirections(facts, inventories);
            if (directions != null) result.add(directions);
        }
        return result;
    }

    /** Update the direction on existing Interface Fact Sheets in the relations stage. */
    private static Workbook updateInterfaceDirections(List<Map<String, String>> facts,
            Map<String, Inventory> inventories) throws IOException {
        List<Map<String, String>> interfaces = new ArrayList<>();
        for (Map<String, String> fact : facts) {
            if ("interface".equals(value(fact, "factSheetTypeKey"))
                    && !value(fact, "dataFlowDirection").isBlank()) {
                interfaces.add(fact);
            }
        }
        if (interfaces.isEmpty()) return null;

        Inventory inventory = inventories.get("interface");
        if (inventory == null) throw new IOException("Missing Interface.xlsx workspace inventory");
        List<String> columns = Arrays.asList("id", "type", "name", "dataFlowDirection");
        List<List<String>> rows = new ArrayList<>();
        for (Map<String, String> fact : interfaces) {
            Map<String, String> existing = uniqueMatch(inventory, value(fact, "name"));
            if (value(existing, "id").isBlank()) {
                throw new IOException("Missing workspace ID for Interface: " + value(fact, "name"));
            }
            Map<String, String> update = new HashMap<>();
            update.put("id", value(existing, "id"));
            update.put("type", value(existing, "type"));
            update.put("name", value(existing, "name"));
            update.put("dataFlowDirection", leanixDirection(value(fact, "dataFlowDirection")));
            rows.add(values(update, columns));
        }
        List<String> displayLabels = labels(inventory, columns);
        if (!inventory.has("dataFlowDirection")) {
            displayLabels.set(3, "Data Flow Direction");
        }
        return new Workbook("03_RELATIONS_Interface.xlsx",
                columns, displayLabels, rows);
    }

    private static Map<String, String> uniqueMatch(Inventory inventory, String name)
            throws IOException {
        List<Map<String, String>> matches = inventory.find(name);
        if (matches.size() != 1) {
            throw new IOException("Expected one workspace record for '" + name + "'; found " + matches.size());
        }
        return matches.get(0);
    }

    private static String requireType(String key) throws IOException {
        String type = TYPES.get(key);
        if (type == null) throw new IOException("Unknown Fact Sheet type: " + key);
        return type;
    }

    private static List<String> labels(Inventory inventory, List<String> columns) {
        List<String> result = new ArrayList<>();
        for (String name : columns) result.add(inventory.label(name));
        return result;
    }

    private static List<String> values(Map<String, String> record, List<String> columns) {
        List<String> result = new ArrayList<>();
        for (String name : columns) result.add(value(record, name));
        return result;
    }

    private static String value(Map<String, String> record, String field) {
        return record.getOrDefault(field, "").trim();
    }

    private static boolean invalidRelationText(String text) {
        return text.contains(";") || text.contains("<");
    }

    private static boolean indirectPath(String from, String to, Map<String, Set<String>> edges) {
        Deque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        for (String child : edges.getOrDefault(from, Collections.emptySet())) {
            if (!child.equals(to)) queue.add(child);
        }
        while (!queue.isEmpty()) {
            String current = queue.remove();
            if (!visited.add(current)) continue;
            if (current.equals(to)) return true;
            queue.addAll(edges.getOrDefault(current, Collections.emptySet()));
        }
        return false;
    }

    private static boolean ambiguousAncestor(String id, Map<String, String> parent, Set<String> ambiguous) {
        Set<String> seen = new HashSet<>();
        while (id != null && seen.add(id)) {
            if (ambiguous.contains(id)) return true;
            id = parent.get(id);
        }
        return id != null;
    }

    private static int depth(String id, Map<String, String> parent, Set<String> visited)
            throws IOException {
        if (!visited.add(id)) throw new IOException("Hierarchy contains a cycle at " + id);
        String p = parent.get(id);
        return p == null ? 0 : 1 + depth(p, parent, visited);
    }

    private static void saveWorkbooks(Path out, String prefix, List<Workbook> workbooks) throws IOException {
        Files.createDirectories(out);
        Path temp = Files.createTempDirectory(out, ".excel-export-");
        try {
            for (Workbook workbook : workbooks) {
                writeExcel(temp.resolve(workbook.filename), workbook);
            }
            try {
                for (Workbook workbook : workbooks) {
                    Files.move(temp.resolve(workbook.filename), out.resolve(workbook.filename),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException ex) {
                try (DirectoryStream<Path> existing = Files.newDirectoryStream(out, prefix + "*.xlsx")) {
                    for (Path file : existing) Files.deleteIfExists(file);
                }
                throw ex;
            }
        } finally {
            try (DirectoryStream<Path> leftovers = Files.newDirectoryStream(temp)) {
                for (Path file : leftovers) Files.deleteIfExists(file);
            }
            Files.deleteIfExists(temp);
        }
    }

    private static void removeOldStageFiles(Path out, String prefix) throws IOException {
        try (DirectoryStream<Path> existing = Files.newDirectoryStream(out, "*.xlsx")) {
            for (Path file : existing) {
                String name = file.getFileName().toString();
                if (name.startsWith(prefix) || name.startsWith("DRAFT_" + prefix)) {
                    Files.delete(file);
                }
            }
        }
    }

    private static void requireColumns(List<Map<String, String>> rows, String... columns)
            throws IOException {
        if (rows.isEmpty()) throw new IOException("Input CSV is empty");
        for (String col : columns) if (!rows.get(0).containsKey(col)) {
            throw new IOException("Input CSV missing column " + col);
        }
    }

    private static void requireFields(Inventory inventory, String... columns) throws IOException {
        for (String col : columns) if (!inventory.has(col)) {
            throw new IOException("LeanIX Excel export missing column " + col);
        }
    }

    private static List<Map<String, String>> readCsv(Path path) throws IOException {
        if (!Files.isRegularFile(path)) throw new IOException("Missing CSV: " + path);
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (text.startsWith("\ufeff")) text = text.substring(1);
        List<List<String>> lines = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"'); i++;
                } else if (ch == '"') quoted = false;
                else cell.append(ch);
            } else if (ch == '"') quoted = true;
            else if (ch == ',') { row.add(cell.toString()); cell.setLength(0); }
            else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); cell.setLength(0);
                lines.add(row); row = new ArrayList<>();
            } else cell.append(ch);
        }
        if (quoted) throw new IOException("Unterminated quoted field in " + path);
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); lines.add(row); }
        if (lines.isEmpty()) throw new IOException("Empty CSV: " + path);
        List<Map<String, String>> result = new ArrayList<>();
        List<String> headings = lines.get(0);
        for (int i = 1; i < lines.size(); i++) {
            List<String> line = lines.get(i);
            if (line.size() == 1 && line.get(0).isEmpty()) continue;
            Map<String, String> record = new LinkedHashMap<>();
            for (int n = 0; n < headings.size(); n++) {
                record.put(headings.get(n), n < line.size() ? line.get(n) : "");
            }
            result.add(record);
        }
        return result;
    }

    private static Inventory readExcel(Path path) throws Exception {
        if (!Files.isRegularFile(path)) throw new IOException("Missing LeanIX Excel export: " + path);
        List<List<String>> grid = new ArrayList<>();
        try (ZipFile zip = new ZipFile(path.toFile())) {
            List<String> shared = new ArrayList<>();
            ZipEntry stringsFile = zip.getEntry("xl/sharedStrings.xml");
            if (stringsFile != null) {
                Document strings = parseXml(zip.getInputStream(stringsFile));
                NodeList items = strings.getElementsByTagNameNS("*", "si");
                for (int i = 0; i < items.getLength(); i++) {
                    shared.add(textOf((Element) items.item(i)));
                }
            }
            ZipEntry firstSheet = zip.getEntry("xl/worksheets/sheet1.xml");
            if (firstSheet == null) throw new IOException("Missing Excel worksheet in " + path);
            Document sheet = parseXml(zip.getInputStream(firstSheet));
            NodeList sheetRows = sheet.getElementsByTagNameNS("*", "row");
            for (int i = 0; i < sheetRows.getLength(); i++) {
                Map<Integer, String> cells = new HashMap<>();
                int lastColumn = -1;
                for (Node node = sheetRows.item(i).getFirstChild(); node != null; node = node.getNextSibling()) {
                    if (!(node instanceof Element) || !"c".equals(node.getLocalName())) continue;
                    Element cell = (Element) node;
                    int column = columnIndex(cell.getAttribute("r"));
                    String text = "";
                    Element v = firstChild(cell, "v");
                    if ("inlineStr".equals(cell.getAttribute("t"))) {
                        Element inline = firstChild(cell, "is");
                        if (inline != null) text = textOf(inline);
                    } else if (v != null) {
                        text = v.getTextContent();
                        if ("s".equals(cell.getAttribute("t"))) text = shared.get(Integer.parseInt(text));
                    }
                    cells.put(column, text);
                    lastColumn = Math.max(lastColumn, column);
                }
                List<String> row = new ArrayList<>();
                for (int n = 0; n <= lastColumn; n++) row.add(cells.getOrDefault(n, ""));
                grid.add(row);
            }
        }
        if (grid.size() < 2) throw new IOException("LeanIX export needs two header rows: " + path);
        Inventory inventory = new Inventory();
        inventory.columns.addAll(grid.get(0));
        inventory.labels.addAll(grid.get(1));
        requireFields(inventory, "type", "name");
        for (int i = 2; i < grid.size(); i++) {
            Map<String, String> row = new LinkedHashMap<>();
            boolean nonEmpty = false;
            for (int n = 0; n < inventory.columns.size(); n++) {
                String cell = n < grid.get(i).size() ? grid.get(i).get(n) : "";
                row.put(inventory.columns.get(n), cell);
                if (!cell.isBlank()) nonEmpty = true;
            }
            if (nonEmpty) inventory.rows.add(row);
        }
        return inventory;
    }

    private static Document parseXml(InputStream input) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try (InputStream stream = input) { return factory.newDocumentBuilder().parse(stream); }
    }

    private static Element firstChild(Element parent, String tag) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element && tag.equals(node.getLocalName())) return (Element) node;
        }
        return null;
    }

    private static String textOf(Element parent) {
        StringBuilder out = new StringBuilder();
        NodeList fragments = parent.getElementsByTagNameNS("*", "t");
        for (int i = 0; i < fragments.getLength(); i++) out.append(fragments.item(i).getTextContent());
        return out.toString();
    }

    private static int columnIndex(String cellRef) {
        int index = 0;
        for (int i = 0; i < cellRef.length(); i++) {
            char ch = Character.toUpperCase(cellRef.charAt(i));
            if (ch < 'A' || ch > 'Z') break;
            index = 26 * index + (ch - 'A' + 1);
        }
        return index - 1;
    }

    private static String columnName(int index) {
        StringBuilder out = new StringBuilder();
        for (index++; index > 0; index /= 26) {
            index--;
            out.append((char) ('A' + index % 26));
        }
        return out.reverse().toString();
    }

    private static void writeExcel(Path path, Workbook workbook) throws IOException {
        List<List<String>> lines = new ArrayList<>();
        lines.add(workbook.columns);
        lines.add(workbook.labels);
        lines.addAll(workbook.rows);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path), StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                    + "</Types>");
            put(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "</Relationships>");
            put(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                    + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                    + "<sheets><sheet name=\"Import\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            put(zip, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                    + "</Relationships>");
            StringBuilder sheet = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
            for (int row = 0; row < lines.size(); row++) {
                sheet.append("<row r=\"").append(row + 1).append("\">");
                List<String> cells = lines.get(row);
                for (int col = 0; col < cells.size(); col++) {
                    String val = cells.get(col);
                    if (val == null || val.isEmpty()) continue;
                    sheet.append("<c r=\"").append(columnName(col)).append(row + 1)
                            .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                            .append(xmlEscape(val)).append("</t></is></c>");
                }
                sheet.append("</row>");
            }
            sheet.append("</sheetData></worksheet>");
            put(zip, "xl/worksheets/sheet1.xml", sheet.toString());
        }
    }

    private static String xmlEscape(String raw) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '&') escaped.append("&amp;");
            else if (ch == '<') escaped.append("&lt;");
            else if (ch == '>') escaped.append("&gt;");
            else if (ch == '"') escaped.append("&quot;");
            else if (ch == '\'') escaped.append("&apos;");
            else if (ch == '\n' || ch == '\r' || ch == '\t' || ch >= 0x20) escaped.append(ch);
        }
        return escaped.toString();
    }

    private static void put(ZipOutputStream zip, String path, String contents) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(contents.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
