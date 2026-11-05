package com.sbt.pprbod.xsd;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Исключения второго этапа валидации XSD: все тексты ошибок {@link XsdFacetValidator} в одном месте.
 *
 * <p>Каждый метод отвечает за один вид нарушения и возвращает готовое исключение — вызывающий код
 * пишет {@code throw XsdExpGenerator.createUnrestrictedStringException(...)}. Так компилятор видит, что после вызова
 * выполнение не продолжается.
 *
 * <p>Формат сообщения: {@code [файл]: <место>: <проблема>}. Место строится по тегу схемы:
 * <ul>
 *   <li>{@code <xs:element name="name"> type="xs:string"} — тег с именем и атрибут, в котором найдена проблема;</li>
 *   <li>{@code <xs:restriction> base="xs:decimal" (в <xs:simpleType name="Amount">)} — у безымянного тега
 *       дописывается ближайший именованный родитель.</li>
 * </ul>
 */
public final class XsdExpGenerator {

    private XsdExpGenerator() {
    }

    /**
     * Строковый тип без ограничений: прямая ссылка на xs:string (type, base, itemType, memberTypes)
     * или xs:restriction base="xs:string" без нужных фасетов.
     *
     * @param tag      тег, в котором найдена проблема
     * @param attrName атрибут со ссылкой на тип: type, base, itemType, memberTypes
     * @param typeName значение атрибута, как оно записано в схеме ("xs:string", "xsd:string")
     */
    public static XsdValidationException createUnrestrictedStringException(String file, Element tag, String attrName, String typeName) {
        return createViolation(file, describePlace(tag, attrName, typeName),
                "строковый тип не ограничен: нужен xs:length, xs:maxLength, xs:enumeration или xs:pattern"
                        + " внутри xs:restriction");
    }

    /**
     * Числовой тип без ограничений (xs:decimal и его потомки без верхнего предела): прямая ссылка
     * или xs:restriction без xs:totalDigits и без пары границ.
     *
     * @param tag      тег, в котором найдена проблема
     * @param attrName атрибут со ссылкой на тип: type, base, itemType, memberTypes
     * @param typeName значение атрибута, как оно записано в схеме ("xs:decimal")
     */
    public static XsdValidationException createUnrestrictedNumberException(String file, Element tag, String attrName, String typeName) {
        return createViolation(file, describePlace(tag, attrName, typeName),
                "числовой тип не ограничен: нужен xs:totalDigits или пара"
                        + " xs:minInclusive/xs:minExclusive и xs:maxInclusive/xs:maxExclusive внутри xs:restriction");
    }

    /**
     * Ссылка на xs:anyType или xs:anySimpleType (type, base, itemType, memberTypes).
     *
     * @param tag      тег, в котором найдена ссылка
     * @param attrName атрибут со ссылкой на тип
     * @param typeName значение атрибута, как оно записано в схеме ("xs:anyType")
     */
    public static XsdValidationException createForbiddenTypeException(String file, Element tag, String attrName, String typeName) {
        return createViolation(file, describePlace(tag, attrName, typeName),
                "тип " + typeName + " разрешает любое содержимое, его использование запрещено");
    }

    /** Тег xs:any: на этом месте в XML может стоять любой элемент. */
    public static XsdValidationException createForbiddenAnyException(String file, Element anyTag) {
        return createViolation(file, describePlace(anyTag, null, null),
                "xs:any разрешает любой элемент, его использование запрещено");
    }

    /**
     * Элемент или атрибут объявлен без типа и неявно получил xs:anyType (элемент)
     * или xs:anySimpleType (атрибут).
     */
    public static XsdValidationException createImplicitTypeException(String file, Element declaration) {
        String implicit = "attribute".equals(declaration.getLocalName()) ? "xs:anySimpleType" : "xs:anyType";
        return createViolation(file, describePlace(declaration, null, null),
                "объявлен без типа и неявно имеет тип " + implicit + ", его использование запрещено; укажите тип");
    }

    /**
     * Файл схемы не удалось разобрать как XML. После первого этапа такого быть не должно,
     * поэтому это ошибка программы, а не нарушение правил.
     */
    public static IllegalStateException createUnreadableFileException(String file, Exception cause) {
        return new IllegalStateException("Failed to parse XSD file: " + file, cause);
    }

    // ------------------------------------------------------------------ формат

    private static XsdValidationException createViolation(String file, String place, String problem) {
        return new XsdValidationException("[" + file + "]: " + place + ": " + problem);
    }

    /**
     * Описание места: {@code <xs:тег name="..."> атрибут="значение"}, а для безымянного тега —
     * ещё ближайший именованный родитель.
     */
    private static String describePlace(Element tag, String attrName, String value) {
        StringBuilder sb = new StringBuilder("<xs:").append(tag.getLocalName());
        if (tag.hasAttribute("name")) {
            sb.append(" name=\"").append(tag.getAttribute("name")).append('"');
        }
        sb.append('>');
        if (attrName != null) {
            sb.append(' ').append(attrName).append("=\"").append(value).append('"');
        }
        if (!tag.hasAttribute("name")) {
            sb.append(describeOwner(tag));
        }
        return sb.toString();
    }

    /** Ближайший именованный предок: " (в <xs:simpleType name="Amount">)". */
    private static String describeOwner(Element tag) {
        for (Node n = tag.getParentNode(); n instanceof Element p; n = p.getParentNode()) {
            if (p.hasAttribute("name")) {
                return " (в <xs:" + p.getLocalName() + " name=\"" + p.getAttribute("name") + "\">)";
            }
        }
        return "";
    }
}
