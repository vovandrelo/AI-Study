package com.sbt.pprbod.xsd;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Вторая (кастомная) валидация XSD-схемы: проверка фасетов и запрещённых конструкций.
 *
 * <p>Принимает {@link XsdSchema} — результат {@link XsdSchemaCreator}, то есть схему, уже прошедшую
 * стандартную валидацию, и содержимое всех её файлов. Каждый файл (основной и подключаемые)
 * проверяется на правила:
 * <ul>
 *   <li>xs:string ограничен xs:length, xs:maxLength, xs:enumeration или xs:pattern;</li>
 *   <li>xs:decimal, xs:integer, xs:negativeInteger, xs:nonNegativeInteger, xs:nonPositiveInteger,
 *       xs:positiveInteger ограничены xs:totalDigits или парой
 *       xs:minInclusive/xs:minExclusive + xs:maxInclusive/xs:maxExclusive;</li>
 *   <li>нет xs:any, xs:anyType, xs:anySimpleType (в том числе неявных: элемент без типа
 *       получает xs:anyType, атрибут без типа — xs:anySimpleType).</li>
 * </ul>
 * На первом нарушении бросается {@link XsdValidationException} с именем файла.
 *
 * <p>Ограничения задаются только через xs:restriction: прямая ссылка на xs:string или числовой тип
 * (type, xs:extension base, xs:list itemType, xs:union memberTypes) — всегда нарушение.
 * Restriction от пользовательского типа не проверяется: его базовый тип проверяется отдельно.
 *
 * <p>Класс без состояния, потокобезопасен.
 */
public class XsdFacetValidator {

    private static final String XS = XMLConstants.W3C_XML_SCHEMA_NS_URI;

    /** Строковые типы: должны быть ограничены. */
    private static final Set<String> STRING_TYPES = Set.of("string");

    /** Числовые типы без верхнего предела: должны быть ограничены. */
    private static final Set<String> NUMERIC_TYPES = Set.of(
            "decimal", "integer", "negativeInteger", "nonNegativeInteger",
            "nonPositiveInteger", "positiveInteger");

    /** Запрещённые «всеядные» типы. */
    private static final Set<String> FORBIDDEN_TYPES = Set.of("anyType", "anySimpleType");

    private static final Set<String> STRING_FACETS = Set.of("length", "maxLength", "enumeration", "pattern");
    private static final Set<String> MIN_FACETS = Set.of("minInclusive", "minExclusive");
    private static final Set<String> MAX_FACETS = Set.of("maxInclusive", "maxExclusive");

    /**
     * Проверяет все файлы схемы.
     *
     * @param xsdSchema схема и содержимое её файлов, созданные {@link XsdSchemaCreator}
     * @throws XsdValidationException при первом найденном нарушении
     */
    public void validate(XsdSchema xsdSchema) {
        Objects.requireNonNull(xsdSchema, "xsdSchema");
        for (Map.Entry<String, byte[]> file : xsdSchema.files().entrySet()) {
            validateFile(file.getKey(), file.getValue());
        }
    }

    // ------------------------------------------------------------------ обход файла

    private void validateFile(String fileName, byte[] content) {
        Document doc = parse(fileName, content);
        NodeList all = doc.getElementsByTagNameNS(XS, "*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            switch (e.getLocalName()) {
                case "element", "attribute" -> checkElementOrAttribute(fileName, e);
                case "restriction" -> checkRestriction(fileName, e);
                case "extension" -> checkTypeRef(fileName, e, "base", e.getAttribute("base"));
                case "list" -> checkTypeRef(fileName, e, "itemType", e.getAttribute("itemType"));
                case "union" -> {
                    String members = e.getAttribute("memberTypes").trim();
                    if (!members.isEmpty()) {
                        for (String member : members.split("\\s+")) {
                            checkTypeRef(fileName, e, "memberTypes", member);
                        }
                    }
                }
                // Запрещённая конструкция: xs:any
                case "any" -> throw violation(fileName, "использование xs:any запрещено" + ownerSuffix(e));
                default -> { /* остальные теги не проверяются */ }
            }
        }
    }

    // ------------------------------------------------------------------ правила

    /** xs:element / xs:attribute: явный type или неявный xs:anyType / xs:anySimpleType (если типа нет). */
    private void checkElementOrAttribute(String fileName, Element e) {
        if (e.hasAttribute("ref")) {
            return; // тип задан у глобального объявления, оно проверяется отдельно
        }
        if (e.hasAttribute("type")) {
            checkTypeRef(fileName, e, "type", e.getAttribute("type"));
            return;
        }
        if (firstXsChild(e, "simpleType") != null || firstXsChild(e, "complexType") != null) {
            return; // вложенный тип проверится, когда обход дойдёт до его restriction/extension
        }
        if ("element".equals(e.getLocalName())) {
            if (!e.hasAttribute("substitutionGroup")) { // иначе тип наследуется от головы группы
                throw violation(fileName, "элемент '" + e.getAttribute("name")
                        + "' объявлен без типа и неявно имеет тип xs:anyType");
            }
        } else {
            throw violation(fileName, "атрибут '" + e.getAttribute("name")
                    + "' объявлен без типа и неявно имеет тип xs:anySimpleType");
        }
    }

    /**
     * Прямая ссылка на встроенный тип (type, xs:extension base, xs:list itemType, xs:union memberTypes).
     * К ней нельзя добавить ограничения, поэтому строковые и числовые типы здесь — всегда нарушение.
     */
    private void checkTypeRef(String fileName, Element e, String attrName, String qName) {
        String builtIn = builtInTypeName(e, qName);
        if (builtIn == null) {
            return;
        }
        String where = "<xs:" + e.getLocalName() + nameSuffix(e) + "> " + attrName + "=\"" + qName + "\""
                + (e.hasAttribute("name") ? "" : ownerSuffix(e));
        // Запрещённые типы
        if (FORBIDDEN_TYPES.contains(builtIn)) {
            throw violation(fileName, where + ": использование xs:" + builtIn + " запрещено");
        }
        // Строки
        if (STRING_TYPES.contains(builtIn)) {
            throw violation(fileName, where + ": для строкового типа должно быть задано ограничение "
                    + "(xs:length, xs:maxLength, xs:enumeration или xs:pattern) через xs:restriction");
        }
        // Числа
        if (NUMERIC_TYPES.contains(builtIn)) {
            throw violation(fileName, where + ": для числового типа должно быть задано ограничение "
                    + "(xs:totalDigits или xs:min* + xs:max*) через xs:restriction");
        }
    }

    /** xs:restriction base="...": проверка наличия нужных фасетов. */
    private void checkRestriction(String fileName, Element r) {
        String base = r.getAttribute("base");
        String builtIn = builtInTypeName(r, base);
        if (builtIn == null) {
            return; // пользовательский базовый тип или вложенный simpleType — проверяются отдельно
        }
        String where = "<xs:restriction base=\"" + base + "\">" + ownerSuffix(r);

        // Запрещённые типы
        if (FORBIDDEN_TYPES.contains(builtIn)) {
            // <xs:complexContent><xs:restriction base="xs:anyType"> — развёрнутая запись обычного
            // complexType, произвольного содержимого она не допускает
            boolean explicitComplexType = "anyType".equals(builtIn)
                    && r.getParentNode() instanceof Element p && "complexContent".equals(p.getLocalName());
            if (!explicitComplexType) {
                throw violation(fileName, where + ": использование xs:" + builtIn + " запрещено");
            }
        // Строки
        } else if (STRING_TYPES.contains(builtIn)) {
            if (!hasAnyFacet(r, STRING_FACETS)) {
                throw violation(fileName, where + ": не задан ни один из параметров "
                        + "xs:length, xs:maxLength, xs:enumeration, xs:pattern");
            }
        // Числа
        } else if (NUMERIC_TYPES.contains(builtIn)) {
            boolean restricted = hasAnyFacet(r, Set.of("totalDigits"))
                    || (hasAnyFacet(r, MIN_FACETS) && hasAnyFacet(r, MAX_FACETS));
            if (!restricted) {
                throw violation(fileName, where + ": должен быть задан xs:totalDigits либо пара "
                        + "xs:minInclusive/xs:minExclusive и xs:maxInclusive/xs:maxExclusive");
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Если qName ссылается на тип из пространства имён XML Schema — возвращает его локальное имя
     * ("string"), иначе null. Префикс разрешается по объявлениям xmlns, поэтому работают
     * и "xs:", и "xsd:", и пространство имён по умолчанию.
     */
    private static String builtInTypeName(Element context, String qName) {
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

    /** Ближайший именованный предок — чтобы было понятно, о каком теге речь. */
    private static String ownerSuffix(Element e) {
        for (Node n = e.getParentNode(); n instanceof Element p; n = p.getParentNode()) {
            if (p.hasAttribute("name")) {
                return " (в <xs:" + p.getLocalName() + " name=\"" + p.getAttribute("name") + "\">)";
            }
        }
        return "";
    }

    private static XsdValidationException violation(String fileName, String message) {
        return new XsdValidationException("[" + fileName + "]: " + message);
    }

    private static Document parse(String fileName, byte[] content) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            return dbf.newDocumentBuilder().parse(new ByteArrayInputStream(content));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            // Схема уже скомпилировалась, так что сюда попадать не должны
            throw new IllegalStateException("Failed to parse XSD file: " + fileName, e);
        }
    }
}
