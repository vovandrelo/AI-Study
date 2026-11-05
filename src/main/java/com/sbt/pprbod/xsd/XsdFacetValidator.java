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
 * На первом нарушении бросается {@link XsdValidationException}; тексты ошибок — в {@link XsdExpGenerator}.
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
    private static final Set<String> DIGITS_FACETS = Set.of("totalDigits");
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
                case "union" -> checkUnion(fileName, e);
                // Запрещённая конструкция: xs:any
                case "any" -> throw XsdExpGenerator.createForbiddenAnyException(fileName, e);
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
        if (findFirstXsChild(e, "simpleType") != null || findFirstXsChild(e, "complexType") != null) {
            return; // вложенный тип проверится, когда обход дойдёт до его restriction/extension
        }
        if ("element".equals(e.getLocalName()) && e.hasAttribute("substitutionGroup")) {
            return; // тип наследуется от головы группы подстановки
        }
        throw XsdExpGenerator.createImplicitTypeException(fileName, e);
    }

    /**
     * xs:union memberTypes="...": каждый тип из списка проверяется как прямая ссылка.
     * Ветки, объявленные вложенными xs:simpleType, проверятся, когда обход дойдёт до их restriction.
     */
    private void checkUnion(String fileName, Element union) {
        String members = union.getAttribute("memberTypes").trim();
        if (members.isEmpty()) {
            return; // все ветки заданы вложенными xs:simpleType
        }
        for (String member : members.split("\\s+")) {
            checkTypeRef(fileName, union, "memberTypes", member);
        }
    }

    /**
     * Прямая ссылка на встроенный тип (type, xs:extension base, xs:list itemType, xs:union memberTypes).
     * К ней нельзя добавить ограничения, поэтому строковые и числовые типы здесь — всегда нарушение.
     */
    private void checkTypeRef(String fileName, Element e, String attrName, String qName) {
        String builtIn = getBuiltInTypeName(e, qName);
        if (builtIn == null) {
            return;
        }
        // Запрещённые типы
        if (FORBIDDEN_TYPES.contains(builtIn)) {
            throw XsdExpGenerator.createForbiddenTypeException(fileName, e, attrName, qName);
        }
        // Строки
        if (STRING_TYPES.contains(builtIn)) {
            throw XsdExpGenerator.createUnrestrictedStringException(fileName, e, attrName, qName);
        }
        // Числа
        if (NUMERIC_TYPES.contains(builtIn)) {
            throw XsdExpGenerator.createUnrestrictedNumberException(fileName, e, attrName, qName);
        }
    }

    /** xs:restriction base="...": проверка наличия нужных фасетов. */
    private void checkRestriction(String fileName, Element r) {
        String base = r.getAttribute("base");
        String builtIn = getBuiltInTypeName(r, base);
        if (builtIn == null) {
            return; // пользовательский базовый тип или вложенный simpleType — проверяются отдельно
        }
        // Запрещённые типы
        if (FORBIDDEN_TYPES.contains(builtIn)) {
            // <xs:complexContent><xs:restriction base="xs:anyType"> — развёрнутая запись обычного
            // complexType, произвольного содержимого она не допускает
            boolean explicitComplexType = "anyType".equals(builtIn)
                    && r.getParentNode() instanceof Element p && "complexContent".equals(p.getLocalName());
            if (!explicitComplexType) {
                throw XsdExpGenerator.createForbiddenTypeException(fileName, r, "base", base);
            }
        // Строки
        } else if (STRING_TYPES.contains(builtIn)) {
            if (!hasAnyFacet(r, STRING_FACETS)) {
                throw XsdExpGenerator.createUnrestrictedStringException(fileName, r, "base", base);
            }
        // Числа
        } else if (NUMERIC_TYPES.contains(builtIn)) {
            boolean restricted = hasAnyFacet(r, DIGITS_FACETS)
                    || (hasAnyFacet(r, MIN_FACETS) && hasAnyFacet(r, MAX_FACETS));
            if (!restricted) {
                throw XsdExpGenerator.createUnrestrictedNumberException(fileName, r, "base", base);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Если qName ссылается на тип из пространства имён XML Schema — возвращает его локальное имя
     * ("string"), иначе null. Префикс разрешается по объявлениям xmlns, поэтому работают
     * и "xs:", и "xsd:", и пространство имён по умолчанию.
     */
    private static String getBuiltInTypeName(Element context, String qName) {
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

    private static Element findFirstXsChild(Element parent, String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element c && XS.equals(c.getNamespaceURI()) && localName.equals(c.getLocalName())) {
                return c;
            }
        }
        return null;
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
            throw XsdExpGenerator.createUnreadableFileException(fileName, e);
        }
    }
}
