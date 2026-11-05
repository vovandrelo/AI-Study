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
import java.util.Objects;
import java.util.Set;

/**
 * Вторая (кастомная) валидация XSD-схемы: проверка фасетов и запрещённых конструкций.
 * (javadoc класса без изменений)
 */
public class XsdFacetValidator {

    /** Пространство имён XML Schema: http://www.w3.org/2001/XMLSchema. */
    private static final String XSD_NAMESPACE = XMLConstants.W3C_XML_SCHEMA_NS_URI;

    /** Строковые типы: должны быть ограничены. */
    private static final Set<String> STRING_TYPES = Set.of("string");

    /** Числовые типы без верхнего предела: должны быть ограничены. */
    private static final Set<String> NUMERIC_TYPES = Set.of(
            "decimal", "integer", "negativeInteger", "nonNegativeInteger",
            "nonPositiveInteger", "positiveInteger");

    /** Запрещённые «всеядные» типы. */
    private static final Set<String> FORBIDDEN_TYPES = Set.of("anyType", "anySimpleType");

    private static final Set<String> STRING_FACETS = Set.of("length", "maxLength", "enumeration", "pattern");
    private static final Set<String> TOTAL_DIGITS_FACETS = Set.of("totalDigits");
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
        xsdSchema.files().forEach(this::validateFile);
    }

    // ------------------------------------------------------------------ обход файла

    /** Обходит все теги XML Schema в файле и проверяет те, что касаются правил. */
    private void validateFile(String fileName, byte[] fileContent) {
        Document document = parseDocument(fileName, fileContent);
        NodeList xsdTags = document.getElementsByTagNameNS(XSD_NAMESPACE, "*");
        for (int i = 0; i < xsdTags.getLength(); i++) {
            Element tag = (Element) xsdTags.item(i);
            switch (tag.getLocalName()) {
                case "element", "attribute" -> checkElementOrAttribute(fileName, tag);
                case "restriction" -> checkRestriction(fileName, tag);
                case "extension" -> checkTypeReference(fileName, tag, "base", tag.getAttribute("base"));
                case "list" -> checkTypeReference(fileName, tag, "itemType", tag.getAttribute("itemType"));
                case "union" -> checkUnion(fileName, tag);
                // Запрещённая конструкция: xs:any
                case "any" -> throw XsdExpGenerator.createForbiddenAnyException(fileName, tag);
                default -> { /* остальные теги не проверяются */ }
            }
        }
    }

    // ------------------------------------------------------------------ правила

    /** xs:element / xs:attribute: явный type или неявный xs:anyType / xs:anySimpleType (если типа нет). */
    private void checkElementOrAttribute(String fileName, Element declaration) {
        if (declaration.hasAttribute("ref")) {
            return; // тип задан у глобального объявления, оно проверяется отдельно
        }
        if (declaration.hasAttribute("type")) {
            checkTypeReference(fileName, declaration, "type", declaration.getAttribute("type"));
            return;
        }
        if (findXsdChild(declaration, "simpleType") != null || findXsdChild(declaration, "complexType") != null) {
            return; // вложенный тип проверится, когда обход дойдёт до его restriction/extension
        }
        if ("element".equals(declaration.getLocalName()) && declaration.hasAttribute("substitutionGroup")) {
            return; // тип наследуется от головы группы подстановки
        }
        throw XsdExpGenerator.createImplicitTypeException(fileName, declaration);
    }

    /**
     * xs:union memberTypes="...": каждый тип из списка проверяется как прямая ссылка.
     * Ветки, объявленные вложенными xs:simpleType, проверятся, когда обход дойдёт до их restriction.
     */
    private void checkUnion(String fileName, Element union) {
        String memberTypes = union.getAttribute("memberTypes").trim();
        if (memberTypes.isEmpty()) {
            return; // все ветки заданы вложенными xs:simpleType
        }
        for (String memberType : memberTypes.split("\\s+")) {
            checkTypeReference(fileName, union, "memberTypes", memberType);
        }
    }

    /**
     * Прямая ссылка на встроенный тип (type, xs:extension base, xs:list itemType, xs:union memberTypes).
     * К ней нельзя добавить ограничения, поэтому строковые и числовые типы здесь — всегда нарушение.
     *
     * @param tag           тег, в котором стоит ссылка
     * @param attributeName атрибут со ссылкой: type, base, itemType, memberTypes
     * @param typeReference имя типа, как оно записано в схеме: "xs:string", "xsd:decimal", "tns:Code"
     */
    private void checkTypeReference(String fileName, Element tag, String attributeName, String typeReference) {
        String builtInTypeName = getBuiltInTypeName(tag, typeReference);
        if (builtInTypeName == null) {
            return; // пользовательский тип — проверяется там, где объявлен
        }
        // Запрещённые типы
        if (FORBIDDEN_TYPES.contains(builtInTypeName)) {
            throw XsdExpGenerator.createForbiddenTypeException(fileName, tag, attributeName, typeReference);
        }
        // Строки
        if (STRING_TYPES.contains(builtInTypeName)) {
            throw XsdExpGenerator.createUnrestrictedStringException(fileName, tag, attributeName, typeReference);
        }
        // Числа
        if (NUMERIC_TYPES.contains(builtInTypeName)) {
            throw XsdExpGenerator.createUnrestrictedNumberException(fileName, tag, attributeName, typeReference);
        }
    }

    /** xs:restriction base="...": проверка наличия нужных фасетов. */
    private void checkRestriction(String fileName, Element restriction) {
        String baseType = restriction.getAttribute("base");
        String builtInTypeName = getBuiltInTypeName(restriction, baseType);
        if (builtInTypeName == null) {
            return; // пользовательский базовый тип или вложенный simpleType — проверяются отдельно
        }
        // Запрещённые типы
        if (FORBIDDEN_TYPES.contains(builtInTypeName)) {
            // <xs:complexContent><xs:restriction base="xs:anyType"> — развёрнутая запись обычного
            // complexType, произвольного содержимого она не