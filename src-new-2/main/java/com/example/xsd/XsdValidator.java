package com.example.xsd;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.Attributes;
import org.xml.sax.ErrorHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Валидатор XSD-схемы (валидируется сама схема, а не XML-документы по ней).
 *
 * <p><b>Откуда берётся схема.</b> На вход приходит абсолютный путь к главному XSD-файлу либо ничего:
 * <ul>
 *   <li>путь задан — главный файл читается с диска;</li>
 *   <li>путь не задан — главный файл берётся из classpath (resources), имя задаётся в конструкторе.</li>
 * </ul>
 * Все файлы, подключаемые через xs:import / xs:include, по соглашению лежат в той же папке,
 * что и главный файл. Поэтому из schemaLocation берётся только имя файла
 * ("common.xsd", "sub/common.xsd" и "http://host/x/common.xsd" → "common.xsd"),
 * и файл ищется рядом с главным. Подгружает их resolver автоматически во время компиляции.
 *
 * <p><b>Этап 1</b> — стандартная валидация: главный файл компилируется через
 * {@link SchemaFactory#newSchema(javax.xml.transform.Source)} вместе со всеми импортами.
 * Компилятор сам проверяет корректность схемы: теги и атрибуты, существование типов,
 * применимость фасетов, наличие импортируемых файлов.
 *
 * <p><b>Этап 2</b> (только если этап 1 пройден) — кастомные проверки во ВСЕХ загруженных файлах:
 * <ol>
 *   <li>строковые типы ограничены length / maxLength / enumeration / pattern;</li>
 *   <li>числовые типы ограничены totalDigits или парой min* + max*;</li>
 *   <li>нет xs:any, xs:anyType, xs:anySimpleType (в т.ч. неявных).</li>
 * </ol>
 *
 * <p>Экземпляр потокобезопасен: состояние каждого вызова создаётся заново.
 */
public class XsdValidator {

    /** Главный XSD в resources по умолчанию (используется, когда путь не передан). */
    public static final String DEFAULT_MAIN_RESOURCE = "xsd/main.xsd";

    private static final String XS = XMLConstants.W3C_XML_SCHEMA_NS_URI;

    /** Условная схема для systemId загруженных файлов: "xsd:/main.xsd", "xsd:/common.xsd". */
    private static final String SYSTEM_ID_PREFIX = "xsd:/";

    private static final String LINE_KEY = "line";
    private static final String COLUMN_KEY = "column";

    /** Типы, требующие length / maxLength / enumeration / pattern. */
    private static final Set<String> STRING_TYPES = Set.of("string");

    /** Типы, требующие totalDigits или min(Inclusive|Exclusive) + max(Inclusive|Exclusive). */
    private static final Set<String> NUMERIC_TYPES = Set.of(
            "decimal", "integer", "negativeInteger", "nonNegativeInteger",
            "nonPositiveInteger", "positiveInteger");

    /** Запрещённые типы. */
    private static final Set<String> FORBIDDEN_TYPES = Set.of("anyType", "anySimpleType");

    private static final Set<String> STRING_FACETS = Set.of("length", "maxLength", "enumeration", "pattern");
    private static final Set<String> MIN_FACETS = Set.of("minInclusive", "minExclusive");
    private static final Set<String> MAX_FACETS = Set.of("maxInclusive", "maxExclusive");

    /** Путь к главному XSD в classpath, без ведущего "/", например "xsd/main.xsd". */
    private final String mainResource;

    public XsdValidator() {
        this(DEFAULT_MAIN_RESOURCE);
    }

    /**
     * @param mainResource путь к главному XSD в classpath (resources), например "xsd/main.xsd";
     *                     используется, когда в {@link #validate(String)} путь не передан
     */
    public XsdValidator(String mainResource) {
        Objects.requireNonNull(mainResource, "mainResource");
        this.mainResource = mainResource.startsWith("/") ? mainResource.substring(1) : mainResource;
    }

    // ------------------------------------------------------------------ API

    /** Валидирует главный XSD из resources (см. конструктор) вместе с его импортами. */
    public ValidationResult validate() {
        return validate(null);
    }

    /**
     * Валидирует XSD вместе с его импортами.
     *
     * @param mainXsdPath абсолютный путь к главному XSD-файлу; если null или пустой —
     *                    главный XSD берётся из resources
     * @throws IllegalArgumentException если путь не абсолютный
     * @throws UncheckedIOException     если главный файл не найден или не читается
     */
    public ValidationResult validate(String mainXsdPath) {
        XsdFolder folder = (mainXsdPath == null || mainXsdPath.isBlank())
                ? classpathFolder()
                : fileSystemFolder(mainXsdPath);

        byte[] mainXsd = folder.readRequired(folder.mainFileName());

        // Все файлы схемы в порядке загрузки: главный + подгруженные resolver'ом импорты
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(folder.mainFileName(), mainXsd);

        List<Violation> standard = standardValidation(folder, mainXsd, files);
        if (!standard.isEmpty()) {
            return new ValidationResult(Stage.STANDARD, standard);
        }

        List<Violation> custom = new ArrayList<>();
        files.forEach((fileName, content) -> custom.addAll(customValidation(fileName, content)));
        if (!custom.isEmpty()) {
            return new ValidationResult(Stage.CUSTOM, custom);
        }
        return new ValidationResult(null, List.of());
    }

    // ------------------------------------------------- откуда читаются файлы

    /** Папка, в которой лежат главный XSD и все его импорты. */
    private interface XsdFolder {

        String mainFileName();

        /** Содержимое файла из папки или null, если файла нет. */
        byte[] read(String fileName) throws IOException;

        /** Описание папки для сообщений об ошибках. */
        String description();

        default byte[] readRequired(String fileName) {
            try {
                byte[] content = read(fileName);
                if (content == null) {
                    throw new UncheckedIOException(new IOException(
                            "Главный XSD не найден: " + fileName + " (" + description() + ")"));
                }
                return content;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static XsdFolder fileSystemFolder(String mainXsdPath) {
        Path mainFile = Path.of(mainXsdPath);
        if (!mainFile.isAbsolute()) {
            throw new IllegalArgumentException("Ожидался абсолютный путь к XSD, получено: " + mainXsdPath);
        }
        Path dir = mainFile.normalize().getParent();
        String mainName = mainFile.getFileName().toString();
        return new XsdFolder() {
            @Override
            public String mainFileName() {
                return mainName;
            }

            @Override
            public byte[] read(String fileName) throws IOException {
                Path file = dir.resolve(fileName);
                if (!Files.isRegularFile(file)) {
                    return null;
                }
                try (InputStream in = Files.newInputStream(file)) {
                    return in.readAllBytes();
                }
            }

            @Override
            public String description() {
                return "папка " + dir;
            }
        };
    }

    private XsdFolder classpathFolder() {
        int slash = mainResource.lastIndexOf('/');
        String dir = slash >= 0 ? mainResource.substring(0, slash + 1) : "";
        String mainName = mainResource.substring(slash + 1);
        return new XsdFolder() {
            @Override
            public String mainFileName() {
                return mainName;
            }

            @Override
            public byte[] read(String fileName) throws IOException {
                try (InputStream in = XsdValidator.class.getResourceAsStream("/" + dir + fileName)) {
                    return in == null ? null : in.readAllBytes();
                }
            }

            @Override
            public String description() {
                return "resources:/" + dir;
            }
        };
    }

    // ------------------------------------------- Этап 1: стандартная валидация

    /**
     * Компилирует главный XSD; импорты подгружает {@link FolderResolver}.
     * Важно: если ErrorHandler не бросает исключение, newSchema() возвращает объект
     * даже для невалидной схемы — поэтому результат определяется по списку ошибок.
     */
    private List<Violation> standardValidation(XsdFolder folder, byte[] mainXsd, Map<String, byte[]> files) {
        List<Violation> errors = new ArrayList<>();
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        try {
            // Сам парсер ничего извне не загружает: импорты отдаёт только наш resolver
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (SAXException ignored) {
            // свойство не поддерживается реализацией — не критично
        }
        factory.setResourceResolver(new FolderResolver(folder, files, errors));
        factory.setErrorHandler(new CollectingErrorHandler(errors));

        StreamSource source = new StreamSource(new ByteArrayInputStream(mainXsd),
                SYSTEM_ID_PREFIX + folder.mainFileName());
        try {
            factory.newSchema(source);
        } catch (SAXParseException e) {
            // фатальная ошибка (например, невалидный XML) — уже добавлена в errors обработчиком
            if (errors.isEmpty()) {
                errors.add(new Violation(fileName(e.getSystemId()), e.getLineNumber(), e.getColumnNumber(),
                        e.getMessage()));
            }
        } catch (SAXException e) {
            errors.add(new Violation(folder.mainFileName(), -1, -1, "Ошибка компиляции XSD: " + e.getMessage()));
        }
        return errors;
    }

    /**
     * Resolver: на каждый xs:import / xs:include отдаёт файл из папки главного XSD.
     * Из schemaLocation берётся только имя файла. Загруженные файлы складываются в {@code files}
     * для этапа 2.
     */
    private static final class FolderResolver implements LSResourceResolver {
        private final XsdFolder folder;
        private final Map<String, byte[]> files;
        private final List<Violation> errors;
        private final DOMImplementationLS ls;

        FolderResolver(XsdFolder folder, Map<String, byte[]> files, List<Violation> errors) {
            this.folder = folder;
            this.files = files;
            this.errors = errors;
            try {
                this.ls = (DOMImplementationLS) DocumentBuilderFactory.newInstance()
                        .newDocumentBuilder().getDOMImplementation();
            } catch (ParserConfigurationException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public LSInput resolveResource(String type, String namespaceURI, String publicId,
                                       String systemId, String baseURI) {
            if (!XS.equals(type) || systemId == null || systemId.isBlank()) {
                return null; // не схема (например, DTD) — не грузим
            }
            String fileName = fileName(systemId);
            String referencedFrom = fileName(baseURI);

            byte[] content = files.get(fileName);
            if (content == null) {
                try {
                    content = folder.read(fileName);
                } catch (IOException | RuntimeException e) {
                    errors.add(new Violation(referencedFrom, -1, -1,
                            "Не удалось прочитать файл схемы '" + fileName + "': " + e.getMessage()));
                    return null;
                }
                if (content == null) {
                    errors.add(new Violation(referencedFrom, -1, -1, "Не найден подключаемый файл схемы '"
                            + fileName + "' (schemaLocation=\"" + systemId + "\", " + folder.description() + ")"));
                    return null;
                }
                files.put(fileName, content);
            }

            LSInput input = ls.createLSInput();
            input.setPublicId(publicId);
            input.setSystemId(SYSTEM_ID_PREFIX + fileName);
            input.setBaseURI(baseURI);
            input.setByteStream(new ByteArrayInputStream(content));
            return input;
        }
    }

    // ---------------------------------------------- Этап 2: кастомные проверки

    private List<Violation> customValidation(String fileName, byte[] xsd) {
        Document doc;
        try {
            doc = parseWithLineNumbers(xsd);
        } catch (Exception e) {
            return List.of(new Violation(fileName, -1, -1, "Не удалось разобрать XSD: " + e.getMessage()));
        }

        List<Violation> violations = new ArrayList<>();
        NodeList all = doc.getElementsByTagNameNS(XS, "*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            switch (e.getLocalName()) {
                case "element", "attribute" -> checkElementOrAttribute(e, violations);
                case "restriction" -> checkRestriction(e, violations);
                case "extension" -> checkTypeRef(e, "base", e.getAttribute("base"), violations);
                case "list" -> checkTypeRef(e, "itemType", e.getAttribute("itemType"), violations);
                case "union" -> {
                    String members = e.getAttribute("memberTypes").trim();
                    if (!members.isEmpty()) {
                        for (String member : members.split("\\s+")) {
                            checkTypeRef(e, "memberTypes", member, violations);
                        }
                    }
                }
                case "any" -> violations.add(violation(e, "Использование xs:any запрещено" + ownerSuffix(e)));
                default -> { /* остальные теги не проверяются */ }
            }
        }
        return violations.stream().map(v -> v.withFile(fileName)).toList();
    }

    /** xs:element / xs:attribute: явный type или неявный anyType/anySimpleType. */
    private void checkElementOrAttribute(Element e, List<Violation> out) {
        if (e.hasAttribute("ref")) {
            return; // тип определяется у глобального объявления, оно проверится отдельно
        }
        if (e.hasAttribute("type")) {
            checkTypeRef(e, "type", e.getAttribute("type"), out);
            return;
        }
        boolean hasInlineType = firstXsChild(e, "simpleType") != null || firstXsChild(e, "complexType") != null;
        if (hasInlineType) {
            return; // вложенный тип проверится, когда обход дойдёт до его restriction/extension
        }
        if ("element".equals(e.getLocalName())) {
            if (!e.hasAttribute("substitutionGroup")) { // иначе тип наследуется от головы группы
                out.add(violation(e, "Элемент '" + e.getAttribute("name")
                        + "' объявлен без типа и неявно имеет тип xs:anyType"));
            }
        } else {
            out.add(violation(e, "Атрибут '" + e.getAttribute("name")
                    + "' объявлен без типа и неявно имеет тип xs:anySimpleType"));
        }
    }

    /**
     * Прямая ссылка на встроенный тип (type, extension/@base, list/@itemType, union/@memberTypes).
     * К такой ссылке нельзя добавить ограничения, поэтому строковые и числовые типы здесь —
     * всегда нарушение: нужно объявить simpleType с xs:restriction.
     */
    private void checkTypeRef(Element e, String attrName, String qName, List<Violation> out) {
        String builtin = builtinTypeName(e, qName);
        if (builtin == null) {
            return;
        }
        String where = "<xs:" + e.getLocalName() + nameSuffix(e) + "> " + attrName + "=\"" + qName + "\""
                + (e.hasAttribute("name") ? "" : ownerSuffix(e));
        if (FORBIDDEN_TYPES.contains(builtin)) {
            out.add(violation(e, where + ": использование xs:" + builtin + " запрещено"));
        } else if (STRING_TYPES.contains(builtin)) {
            out.add(violation(e, where + ": для строкового типа должно быть задано ограничение "
                    + "(xs:length, xs:maxLength, xs:enumeration или xs:pattern) через xs:restriction"));
        } else if (NUMERIC_TYPES.contains(builtin)) {
            out.add(violation(e, where + ": для числового типа должно быть задано ограничение "
                    + "(xs:totalDigits или xs:min* + xs:max*) через xs:restriction"));
        }
    }

    /** xs:restriction base="...": проверка наличия нужных фасетов. */
    private void checkRestriction(Element r, List<Violation> out) {
        String base = r.getAttribute("base");
        String builtin = builtinTypeName(r, base);
        if (builtin == null) {
            return; // пользовательский базовый тип или вложенный simpleType — проверяются отдельно
        }
        String where = "<xs:restriction base=\"" + base + "\">" + ownerSuffix(r);

        if (FORBIDDEN_TYPES.contains(builtin)) {
            // <xs:complexContent><xs:restriction base="xs:anyType"> — это просто развёрнутая запись
            // обычного complexType, произвольного содержимого она не допускает
            boolean explicitComplexType = "anyType".equals(builtin)
                    && r.getParentNode() instanceof Element p && "complexContent".equals(p.getLocalName());
            if (!explicitComplexType) {
                out.add(violation(r, where + ": использование xs:" + builtin + " запрещено"));
            }
        } else if (STRING_TYPES.contains(builtin)) {
            if (!hasAnyFacet(r, STRING_FACETS)) {
                out.add(violation(r, where + ": не задан ни один из параметров "
                        + "xs:length, xs:maxLength, xs:enumeration, xs:pattern"));
            }
        } else if (NUMERIC_TYPES.contains(builtin)) {
            boolean ok = hasAnyFacet(r, Set.of("totalDigits"))
                    || (hasAnyFacet(r, MIN_FACETS) && hasAnyFacet(r, MAX_FACETS));
            if (!ok) {
                out.add(violation(r, where + ": должен быть задан xs:totalDigits либо пара "
                        + "xs:minInclusive/xs:minExclusive и xs:maxInclusive/xs:maxExclusive"));
            }
        }
    }

    // ------------------------------------------------------------- helpers

    /**
     * Имя файла из schemaLocation / systemId: всё после последнего '/' или '\'.
     * "common.xsd", "sub/common.xsd", "http://host/x/common.xsd", "xsd:/common.xsd" → "common.xsd".
     */
    private static String fileName(String location) {
        if (location == null) {
            return null;
        }
        String value = location.trim();
        int cut = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        return cut >= 0 ? value.substring(cut + 1) : value;
    }

    /**
     * Если qName ссылается на тип из пространства имён XML Schema — возвращает его локальное имя
     * (например "string"), иначе null. Префикс резолвится по объявлениям xmlns в документе,
     * поэтому работает и с "xs:", и с "xsd:", и с пространством имён по умолчанию.
     */
    private static String builtinTypeName(Element context, String qName) {
        if (qName == null || qName.isBlank()) {
            return null;
        }
        String value = qName.trim();
        int colon = value.indexOf(':');
        String prefix = colon > 0 ? value.substring(0, colon) : null;
        String local = colon > 0 ? value.substring(colon + 1) : value;
        return XS.equals(context.lookupNamespaceURI(prefix)) ? local : null;
    }

    private static boolean hasAnyFacet(Element restriction, Set<String> facets) {
        for (Node n = restriction.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c && XS.equals(c.getNamespaceURI()) && facets.contains(c.getLocalName())) {
                return true;
            }
        }
        return false;
    }

    private static Element firstXsChild(Element parent, String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c && XS.equals(c.getNamespaceURI()) && localName.equals(c.getLocalName())) {
                return c;
            }
        }
        return null;
    }

    private static String nameSuffix(Element e) {
        return e.hasAttribute("name") ? " name=\"" + e.getAttribute("name") + "\"" : "";
    }

    /** Ближайший именованный предок — чтобы в сообщении было понятно, о каком теге речь. */
    private static String ownerSuffix(Element e) {
        for (Node n = e.getParentNode(); n instanceof Element p; n = p.getParentNode()) {
            if (p.hasAttribute("name")) {
                return " (в <xs:" + p.getLocalName() + " name=\"" + p.getAttribute("name") + "\">)";
            }
        }
        return "";
    }

    /** Нарушение без имени файла — оно проставляется в customValidation. */
    private static Violation violation(Element e, String message) {
        Object line = e.getUserData(LINE_KEY);
        Object column = e.getUserData(COLUMN_KEY);
        return new Violation(null, line instanceof Integer l ? l : -1, column instanceof Integer c ? c : -1, message);
    }

    /** Строит DOM через SAX, сохраняя номер строки/колонки каждого элемента в userData. */
    private static Document parseWithLineNumbers(byte[] xsd) throws Exception {
        SAXParserFactory spf = SAXParserFactory.newInstance();
        spf.setNamespaceAware(true);
        spf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        spf.setFeature("http://xml.org/sax/features/namespace-prefixes", true); // нужны xmlns-атрибуты
        spf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        SAXParser parser = spf.newSAXParser();

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        Document doc = dbf.newDocumentBuilder().newDocument();

        parser.parse(new ByteArrayInputStream(xsd), new DefaultHandler() {
            private final Deque<Element> stack = new ArrayDeque<>();
            private Locator locator;

            @Override
            public void setDocumentLocator(Locator locator) {
                this.locator = locator;
            }

            @Override
            public void startElement(String uri, String localName, String qName, Attributes attrs) {
                Element el = doc.createElementNS(uri.isEmpty() ? null : uri, qName);
                for (int i = 0; i < attrs.getLength(); i++) {
                    String aq = attrs.getQName(i);
                    String ns = (aq.equals("xmlns") || aq.startsWith("xmlns:"))
                            ? XMLConstants.XMLNS_ATTRIBUTE_NS_URI
                            : (attrs.getURI(i).isEmpty() ? null : attrs.getURI(i));
                    el.setAttributeNS(ns, aq, attrs.getValue(i));
                }
                if (locator != null) {
                    el.setUserData(LINE_KEY, locator.getLineNumber(), null);
                    el.setUserData(COLUMN_KEY, locator.getColumnNumber(), null);
                }
                (stack.isEmpty() ? doc : stack.peek()).appendChild(el);
                stack.push(el);
            }

            @Override
            public void endElement(String uri, String localName, String qName) {
                stack.pop();
            }
        });
        return doc;
    }

    /** Собирает ошибки компилятора; файл определяется по systemId ("xsd:/common.xsd" → "common.xsd"). */
    private static final class CollectingErrorHandler implements ErrorHandler {
        private final List<Violation> errors;

        CollectingErrorHandler(List<Violation> errors) {
            this.errors = errors;
        }

        @Override
        public void warning(SAXParseException e) {
            // предупреждения не считаем ошибками
        }

        @Override
        public void error(SAXParseException e) {
            errors.add(toViolation(e));
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXParseException {
            errors.add(toViolation(e));
            throw e;
        }

        private static Violation toViolation(SAXParseException e) {
            return new Violation(fileName(e.getSystemId()), e.getLineNumber(), e.getColumnNumber(), e.getMessage());
        }
    }

    // --------------------------------------------------------------- модели

    public enum Stage { STANDARD, CUSTOM }

    /**
     * Одно нарушение.
     *
     * @param file   имя файла схемы, в котором найдено нарушение (null — неизвестно)
     * @param line   номер строки, -1 — неизвестен
     * @param column номер колонки, -1 — неизвестен
     */
    public record Violation(String file, int line, int column, String message) {

        Violation withFile(String file) {
            return new Violation(file, line, column, message);
        }

        @Override
        public String toString() {
            String where = (file != null ? file : "") + (line > 0 ? (file != null ? ":" : "строка ") + line : "");
            return where.isEmpty() ? message : "[" + where + "] " + message;
        }
    }

    /** failedStage = null, если XSD валидна. */
    public record ValidationResult(Stage failedStage, List<Violation> violations) {
        public ValidationResult {
            violations = Collections.unmodifiableList(new ArrayList<>(violations));
        }

        public boolean isValid() {
            return violations.isEmpty();
        }
    }
}
