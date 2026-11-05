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
     * Проверяет все файлы схемы: основной и подключённые через xs:import / xs:include.
     *
     * <ul>
     *   <li>файлы берутся из {@link XsdSchema#files()} — их собрали резолверы при создании схемы;</li>
     *   <li>файлы проверяются по очереди, в том порядке, в каком их загрузили: основной — первым;</li>
     *   <li>проверка останавливается на первом нарушении — остальные файлы уже не проверяются.</li>
     * </ul>
     *
     * @param xsdSchema схема и содержимое её файлов, созданные {@link XsdSchemaCreator}
     * @throws XsdValidationException при первом найденном нарушении
     * @throws NullPointerException   если xsdSchema равна null
     */
    public void validate(XsdSchema xsdSchema) {
        Objects.requireNonNull(xsdSchema, "xsdSchema");
        xsdSchema.files().forEach(this::validateFile);
    }

    // ------------------------------------------------------------------ обход файла

    /**
     * Проверяет один файл схемы: обходит все его теги XML Schema и отправляет каждый на нужную проверку.
     *
     * <ul>
     *   <li>xs:element, xs:attribute — {@link #checkElementOrAttribute};</li>
     *   <li>xs:restriction — {@link #checkRestriction};</li>
     *   <li>xs:extension — тип из {@code base="..."} через {@link #checkTypeReference};</li>
     *   <li>xs:list — тип из {@code itemType="..."} через {@link #checkTypeReference};</li>
     *   <li>xs:union — {@link #checkUnion};</li>
     *   <li>xs:any — сразу нарушение: на его месте в XML может стоять любой элемент;</li>
     *   <li>остальные теги (xs:sequence, xs:annotation, фасеты и т. д.) — не проверяются.</li>
     * </ul>
     *
     * <p>Теги обходятся в порядке документа, вложенные тоже, поэтому вложенные типы
     * проверяются там же, где встречаются.
     *
     * @param fileName    имя файла — попадает в текст ошибки
     * @param fileContent содержимое файла
     */
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

    /**
     * Проверяет тип, с которым объявлен элемент или атрибут.
     *
     * <ul>
     *   <li>{@code ref="..."} — не проверяется: это ссылка на глобальное объявление,
     *       его тип проверяется там, где оно объявлено;</li>
     *   <li>{@code type="..."} — тип проверяется через {@link #checkTypeReference};</li>
     *   <li>вложенный xs:simpleType / xs:complexType — не проверяется здесь: обход файла
     *       сам дойдёт до его xs:restriction / xs:extension;</li>
     *   <li>элемент с {@code substitutionGroup="..."} без типа — не нарушение:
     *       он получает тип головного элемента группы;</li>
     *   <li>во всех остальных случаях типа нет совсем — нарушение: элемент неявно получает
     *       xs:anyType, атрибут — xs:anySimpleType.</li>
     * </ul>
     *
     * @param declaration тег xs:element или xs:attribute
     */
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
     * Проверяет типы, из которых составлен xs:union.
     *
     * <p>Типы перечисляются через пробел в атрибуте {@code memberTypes="xs:date tns:Code"}.
     * Каждый из них — прямая ссылка на тип, поэтому проверяется через {@link #checkTypeReference}.
     *
     * <p>Типы, объявленные внутри union вложенными xs:simpleType, здесь не проверяются:
     * обход файла сам дойдёт до их xs:restriction. Если memberTypes нет, проверять нечего.
     *
     * @param union тег xs:union
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
     * Проверяет прямую ссылку на тип: {@code type="..."}, {@code xs:extension base="..."},
     * {@code xs:list itemType="..."}, {@code xs:union memberTypes="..."}.
     *
     * <p>К прямой ссылке нельзя добавить фасеты — для этого нужен отдельный тип с xs:restriction.
     * Поэтому:
     * <ul>
     *   <li>xs:anyType, xs:anySimpleType — нарушение: тип разрешает любое содержимое;</li>
     *   <li>xs:string — нарушение: строка остаётся без ограничений;</li>
     *   <li>числовой тип ({@link #NUMERIC_TYPES}) — нарушение: число остаётся без ограничений;</li>
     *   <li>остальные встроенные типы (xs:date, xs:boolean, xs:int и т. д.) — не нарушение;</li>
     *   <li>пользовательский тип ({@code tns:Code}) — не проверяется здесь: он проверяется
     *       там, где объявлен.</li>
     * </ul>
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

    /**
     * Проверяет, что xs:restriction от встроенного типа задаёт нужные ограничения (фасеты).
     *
     * <ul>
     *   <li>{@code base="xs:string"} — нужен хотя бы один из xs:length, xs:maxLength,
     *       xs:enumeration, xs:pattern;</li>
     *   <li>{@code base} — числовой тип ({@link #NUMERIC_TYPES}) — нужен xs:totalDigits
     *       или обе границы: xs:minInclusive/xs:minExclusive и xs:maxInclusive/xs:maxExclusive;</li>
     *   <li>{@code base="xs:anyType"} или {@code "xs:anySimpleType"} — нарушение. Исключение:
     *       xs:restriction base="xs:anyType" внутри xs:complexContent — это полная запись
     *       обычного xs:complexType, любое содержимое она не разрешает;</li>
     *   <li>{@code base} — пользовательский тип или base нет (вложенный xs:simpleType) —
     *       не проверяется: базовый тип проверяется там, где объявлен.</li>
     * </ul>
     *
     * @param restriction тег xs:restriction
     */
    private void checkRestriction(String fileName, Element restriction) {
        String baseType = restriction.getAttribute("base");
        String builtInTypeName = getBuiltInTypeName(restriction, baseType);
        if (builtInTypeName == null) {
            return; // пользовательский базовый тип или вложенный simpleType — проверяются отдельно
        }
        // Запрещённые типы
        if (FORBIDDEN_TYPES.contains(builtInTypeName)) {
            // <xs:complexContent><xs:restriction base="xs:anyType"> — развёрнутая запись обычного
            // complexType, произвольного содержимого она не допускает
            boolean isComplexContentRestriction = "anyType".equals(builtInTypeName)
                    && restriction.getParentNode() instanceof Element parent
                    && "complexContent".equals(parent.getLocalName());
            if (!isComplexContentRestriction) {
                throw XsdExpGenerator.createForbiddenTypeException(fileName, restriction, "base", baseType);
            }
            // Строки
        } else if (STRING_TYPES.contains(builtInTypeName)) {
            if (!hasAnyFacet(restriction, STRING_FACETS)) {
                throw XsdExpGenerator.createUnrestrictedStringException(fileName, restriction, "base", baseType);
            }
            // Числа
        } else if (NUMERIC_TYPES.contains(builtInTypeName)) {
            boolean isRestricted = hasAnyFacet(restriction, TOTAL_DIGITS_FACETS)
                    || (hasAnyFacet(restriction, MIN_FACETS) && hasAnyFacet(restriction, MAX_FACETS));
            if (!isRestricted) {
                throw XsdExpGenerator.createUnrestrictedNumberException(fileName, restriction, "base", baseType);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Определяет, ссылается ли имя типа на встроенный тип XML Schema, и возвращает его имя без префикса.
     *
     * <ul>
     *   <li>{@code "xs:string"} — префикс xs объявлен как пространство XML Schema — вернёт "string";</li>
     *   <li>{@code "xsd:string"} — то же самое с другим префиксом — вернёт "string";</li>
     *   <li>{@code "string"} без префикса — вернёт "string", если XML Schema объявлена
     *       пространством имён по умолчанию ({@code xmlns="http://www.w3.org/2001/XMLSchema"});</li>
     *   <li>{@code "tns:Code"} — префикс указывает на другое пространство имён — вернёт null;</li>
     *   <li>пустая строка или null (атрибута нет) — вернёт null.</li>
     * </ul>
     *
     * <p>Префикс сверяется не по написанию, а по объявлениям xmlns в самом теге и его родителях.
     *
     * @param tag           тег, в котором стоит ссылка — по нему ищутся объявления xmlns
     * @param typeReference имя типа, как оно записано в схеме
     * @return имя встроенного типа без префикса или null, если тип не встроенный
     */
    private static String getBuiltInTypeName(Element tag, String typeReference) {
        if (typeReference == null || typeReference.isBlank()) {
            return null;
        }
        String reference = typeReference.trim();
        int colonIndex = reference.indexOf(':');
        String prefix = colonIndex > 0 ? reference.substring(0, colonIndex) : null;
        String typeName = colonIndex > 0 ? reference.substring(colonIndex + 1) : reference;
        return XSD_NAMESPACE.equals(tag.lookupNamespaceURI(prefix)) ? typeName : null;
    }

    /**
     * Проверяет, есть ли у xs:restriction хотя бы один фасет из списка.
     *
     * <ul>
     *   <li>смотрятся только прямые дочерние теги restriction — вложенные глубже не считаются;</li>
     *   <li>тег считается фасетом, только если он из пространства имён XML Schema;</li>
     *   <li>значение фасета не проверяется: {@code <xs:maxLength value="0"/>} тоже считается.</li>
     * </ul>
     *
     * @param restriction тег xs:restriction
     * @param facetNames  имена фасетов без префикса: "maxLength", "pattern" и т. д.
     * @return true, если найден хотя бы один фасет из списка
     */
    private static boolean hasAnyFacet(Element restriction, Set<String> facetNames) {
        for (Node child = restriction.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element facet
                    && XSD_NAMESPACE.equals(facet.getNamespaceURI())
                    && facetNames.contains(facet.getLocalName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ищет первый прямой дочерний тег XML Schema с заданным именем.
     *
     * <ul>
     *   <li>смотрятся только прямые дочерние теги — вложенные глубже не ищутся;</li>
     *   <li>текст, комментарии и теги из других пространств имён пропускаются.</li>
     * </ul>
     *
     * @param parent  тег, в котором ищем
     * @param tagName имя тега без префикса: "simpleType", "complexType"
     * @return найденный тег или null, если такого нет
     */
    private static Element findXsdChild(Element parent, String tagName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element childTag
                    && XSD_NAMESPACE.equals(childTag.getNamespaceURI())
                    && tagName.equals(childTag.getLocalName())) {
                return childTag;
            }
        }
        return null;
    }

    /**
     * Разбирает содержимое файла схемы в DOM-документ.
     *
     * <ul>
     *   <li>{@code setNamespaceAware(true)} — без этого у тегов нет пространства имён
     *       и поиск тегов XML Schema ничего не найдёт;</li>
     *   <li>{@code FEATURE_SECURE_PROCESSING} — ограничения парсера против вредоносного XML;</li>
     *   <li>{@code load-external-dtd = false} — не загружать внешний DTD, если он указан в файле.</li>
     * </ul>
     *
     * <p>Файл уже прошёл первый этап, то есть это корректный XML. Ошибка разбора здесь означает
     * ошибку программы, а не нарушение правил, поэтому бросается IllegalStateException.
     *
     * @param fileName    имя файла — попадает в текст ошибки
     * @param fileContent содержимое файла
     * @return разобранный документ
     * @throws IllegalStateException если файл не удалось разобрать
     */
    private static Document parseDocument(String fileName, byte[] fileContent) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(fileContent));
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            // Схема уже скомпилировалась, так что сюда попадать не должны
            throw XsdExpGenerator.createUnreadableFileException(fileName, exception);
        }
    }
}