package com.example.xsd;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.Attributes;
import org.xml.sax.ErrorHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Валидатор XSD-файла (валидируется сама схема, а не XML-документы по ней).
 *
 * <p>Этап 1 — стандартная валидация: XSD компилируется через
 * {@link SchemaFactory#newSchema(javax.xml.transform.Source)}. Компилятор сам проверяет,
 * что схема корректна по стандарту XML Schema: допустимые теги и атрибуты, существование
 * типов, применимость фасетов к типам и т.д.
 *
 * <p>Этап 2 (только если этап 1 прошёл успешно) — кастомные проверки:
 * <ol>
 *   <li>строковые типы ограничены length / maxLength / enumeration / pattern;</li>
 *   <li>числовые типы ограничены totalDigits или парой min* + max*;</li>
 *   <li>нет xs:any, xs:anyType, xs:anySimpleType (в т.ч. неявных).</li>
 * </ol>
 *
 * <p>Ограничение: xs:import / xs:include не загружаются (внешний доступ запрещён),
 * поэтому поддерживаются только самодостаточные схемы.
 *
 * <p>Экземпляр потокобезопасен: состояния нет, SchemaFactory создаётся на каждый вызов.
 */
public class XsdValidator {

    private static final String XS = XMLConstants.W3C_XML_SCHEMA_NS_URI;

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

    // ------------------------------------------------------------------ API

    public ValidationResult validate(InputStream xsd) throws IOException {
        return validate(xsd.readAllBytes());
    }

    public ValidationResult validate(byte[] xsd) {
        Objects.requireNonNull(xsd, "xsd");

        List<Violation> standard = standardValidation(xsd);
        if (!standard.isEmpty()) {
            return new ValidationResult(Stage.STANDARD, standard);
        }

        List<Violation> custom = customValidation(xsd);
        if (!custom.isEmpty()) {
            return new ValidationResult(Stage.CUSTOM, custom);
        }
        return new ValidationResult(null, List.of());
    }

    // ------------------------------------------- Этап 1: стандартная валидация

    /**
     * Компилирует XSD. Все ошибки компиляции собираются через ErrorHandler.
     * Важно: если ErrorHandler не бросает исключение, newSchema() возвращает объект
     * даже для невалидной схемы — поэтому результат определяется по списку ошибок.
     */
    private List<Violation> standardValidation(byte[] xsd) {
        List<Violation> errors = new ArrayList<>();
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        try {
            // Схема не должна ничего подгружать извне: ни DTD, ни xs:import/xs:include (защита от XXE)
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (SAXException ignored) {
            // свойство не поддерживается реализацией — не критично
        }
        factory.setErrorHandler(new CollectingErrorHandler(errors));
        try {
            factory.newSchema(new StreamSource(new ByteArrayInputStream(xsd)));
        } catch (SAXParseException e) {
            // фатальная ошибка (например, невалидный XML) — уже добавлена в errors обработчиком
            if (errors.isEmpty()) {
                errors.add(new Violation(e.getLineNumber(), e.getColumnNumber(), e.getMessage()));
            }
        } catch (SAXException e) {
            errors.add(new Violation(-1, -1, "Ошибка компиляции XSD: " + e.getMessage()));
        }
        return errors;
    }

    // ---------------------------------------------- Этап 2: кастомные проверки

    private List<Violation> customValidation(byte[] xsd) {
        Document doc;
        try {
            doc = parseWithLineNumbers(xsd);
        } catch (Exception e) {
            return List.of(new Violation(-1, -1, "Не удалось разобрать XSD: " + e.getMessage()));
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
        return violations;
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

    private static Violation violation(Element e, String message) {
        Object line = e.getUserData(LINE_KEY);
        Object column = e.getUserData(COLUMN_KEY);
        return new Violation(line instanceof Integer l ? l : -1, column instanceof Integer c ? c : -1, message);
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
            errors.add(new Violation(e.getLineNumber(), e.getColumnNumber(), e.getMessage()));
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXParseException {
            errors.add(new Violation(e.getLineNumber(), e.getColumnNumber(), e.getMessage()));
            throw e;
        }
    }

    // --------------------------------------------------------------- модели

    public enum Stage { STANDARD, CUSTOM }

    /** Одно нарушение; line/column = -1, если позиция неизвестна. */
    public record Violation(int line, int column, String message) {
        @Override
        public String toString() {
            return line > 0 ? "[строка " + line + "] " + message : message;
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
