package com.sbt.pprbod.xsd;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Тесты {@link XsdFacetValidator} — второго этапа валидации XSD.
 *
 * <p>Тестовые схемы лежат в src/test/resources/xsd/facet/. В шапке каждого файла подробно описано,
 * что в нём проверяется: что нарушено, почему это нарушение, как исправить и какое сообщение ожидается.
 * Строка с нарушением помечена комментарием «НАРУШЕНИЕ».
 *
 * <p>Валидатор останавливается на первом нарушении, поэтому на каждый сценарий — отдельный файл.
 *
 * <p>{@link XsdSchemaCreator} и resolver'ы здесь не используются: {@link XsdSchema} собирается прямо
 * в тесте (схема компилируется обычной {@link SchemaFactory}, файлы читаются из classpath). Так
 * тестируется только {@link XsdFacetValidator}. Компиляция заодно гарантирует, что каждая тестовая
 * схема корректна по стандарту, то есть прошла бы первый этап.
 */
@DisplayName("XsdFacetValidator")
class XsdFacetValidatorTest {

    private static final String BASE = "xsd/facet/";

    /** Валидатор без состояния, один экземпляр на все тесты. */
    private static final XsdFacetValidator VALIDATOR = new XsdFacetValidator();

    // =====================================================================================
    @Nested
    @DisplayName("Допустимые варианты: валидация проходит")
    class Valid {

        @Test
        @DisplayName("xs:string + xs:maxLength")
        void ok_01_stringMaxLength() {
            // Допустимо: строка ограничена максимальной длиной.
            assertValid("valid/ok-01-string-max-length.xsd");
        }

        @Test
        @DisplayName("xs:string + xs:length")
        void ok_02_stringLength() {
            // Допустимо: строка ограничена точной длиной.
            assertValid("valid/ok-02-string-length.xsd");
        }

        @Test
        @DisplayName("xs:string + xs:enumeration")
        void ok_03_stringEnumeration() {
            // Допустимо: строка ограничена списком допустимых значений.
            assertValid("valid/ok-03-string-enumeration.xsd");
        }

        @Test
        @DisplayName("xs:string + xs:pattern")
        void ok_04_stringPattern() {
            // Допустимо: строка ограничена регулярным выражением.
            assertValid("valid/ok-04-string-pattern.xsd");
        }

        @Test
        @DisplayName("Наследник ограниченного пользовательского типа")
        void ok_05_derivedFromUserType() {
            // Допустимо: тип ShortName сужает пользовательский тип Name и добавляет только xs:minLength.
            assertValid("valid/ok-05-derived-from-user-type.xsd");
        }

        @Test
        @DisplayName("xs:decimal + xs:totalDigits")
        void ok_06_decimalTotalDigits() {
            // Допустимо: число ограничено общим количеством цифр.
            assertValid("valid/ok-06-decimal-total-digits.xsd");
        }

        @Test
        @DisplayName("xs:integer + minInclusive + maxInclusive")
        void ok_07_integerMinMaxInclusive() {
            // Допустимо: целое ограничено обеими границами, включительно.
            assertValid("valid/ok-07-integer-min-max-inclusive.xsd");
        }

        @Test
        @DisplayName("xs:positiveInteger + minExclusive + maxExclusive")
        void ok_08_positiveIntegerMinMaxExclusive() {
            // Допустимо: целое ограничено обеими границами, не включительно.
            assertValid("valid/ok-08-positive-integer-min-max-exclusive.xsd");
        }

        @Test
        @DisplayName("xs:nonPositiveInteger + minInclusive + maxExclusive")
        void ok_09_mixedBounds() {
            // Допустимо: границы разного вида: нижняя включительно, верхняя не включительно.
            assertValid("valid/ok-09-mixed-bounds.xsd");
        }

        @Test
        @DisplayName("xs:negativeInteger / xs:nonNegativeInteger + totalDigits")
        void ok_10_negativeNonNegativeTotalDigits() {
            // Допустимо: два оставшихся числовых типа ограничены через xs:totalDigits.
            assertValid("valid/ok-10-negative-non-negative-total-digits.xsd");
        }

        @Test
        @DisplayName("Встроенные типы вне правил: int, long, date, boolean, token")
        void ok_11_builtinTypesOutsideRules() {
            // Допустимо: элементы со встроенными типами, которых нет в списках правил.
            assertValid("valid/ok-11-builtin-types-outside-rules.xsd");
        }

        @Test
        @DisplayName("complexContent + restriction base=xs:anyType")
        void ok_12_complexContentRestrictionAnyType() {
            // Допустимо: сложный тип записан в длинной форме: restriction от xs:anyType.
            assertValid("valid/ok-12-complex-content-restriction-any-type.xsd");
        }

        @Test
        @DisplayName("ref и substitutionGroup без type")
        void ok_13_refAndSubstitutionGroup() {
            // Допустимо: элемент и атрибут подключаются через ref; элемент группы замены объявлен без type.
            assertValid("valid/ok-13-ref-and-substitution-group.xsd");
        }

        @Test
        @DisplayName("simpleContent + extension от ограниченного типа")
        void ok_14_simpleContentExtensionRestricted() {
            // Допустимо: элемент с текстом и атрибутом: extension от ограниченных Amount, атрибут ограниченного типа.
            assertValid("valid/ok-14-simple-content-extension-restricted.xsd");
        }

        @Test
        @DisplayName("list и union из ограниченных типов")
        void ok_15_listUnionRestricted() {
            // Допустимо: список и объединение собраны из ограниченных типов и xs:date.
            assertValid("valid/ok-15-list-union-restricted.xsd");
        }

        @Test
        @DisplayName("Пустой элемент с атрибутами")
        void ok_16_emptyElementWithAttributes() {
            // Допустимо: сложный тип без дочерних элементов, только с атрибутами.
            assertValid("valid/ok-16-empty-element-with-attributes.xsd");
        }

        @Test
        @DisplayName("xs:anyAttribute не проверяется (нет в требованиях)")
        void ok_17_anyAttributeNotChecked() {
            // Допустимо: элемент разрешает любые атрибуты через xs:anyAttribute.
            assertValid("valid/ok-17-any-attribute-not-checked.xsd");
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("Ограничение строк: length / maxLength / enumeration / pattern")
    class Strings {

        @Test
        @DisplayName("Элемент с type=\"xs:string\"")
        void str_01_elementTypeString() {
            // Нарушено: элемент name объявлен с type="xs:string".
            assertViolation("strings/str-01-element-type-string.xsd",
                    "<xs:element name=\"name\"> type=\"xs:string\"",
                    "для строкового типа должно быть задано ограничение");
        }

        @Test
        @DisplayName("Локальный элемент с type=\"xs:string\"")
        void str_02_localElementTypeString() {
            // Нарушено: вложенный элемент comment внутри complexType объявлен с type="xs:string".
            assertViolation("strings/str-02-local-element-type-string.xsd",
                    "<xs:element name=\"comment\"> type=\"xs:string\"",
                    "для строкового типа должно быть задано ограничение");
        }

        @Test
        @DisplayName("Атрибут с type=\"xs:string\"")
        void str_03_attributeTypeString() {
            // Нарушено: атрибут code объявлен с type="xs:string".
            assertViolation("strings/str-03-attribute-type-string.xsd",
                    "<xs:attribute name=\"code\"> type=\"xs:string\"",
                    "для строкового типа должно быть задано ограничение");
        }

        @Test
        @DisplayName("Глобальный атрибут с type=\"xs:string\"")
        void str_04_globalAttributeTypeString() {
            // Нарушено: глобальный атрибут lang объявлен с type="xs:string".
            assertViolation("strings/str-04-global-attribute-type-string.xsd",
                    "<xs:attribute name=\"lang\"> type=\"xs:string\"",
                    "для строкового типа должно быть задано ограничение");
        }

        @Test
        @DisplayName("restriction base=\"xs:string\" без фасетов")
        void str_05_restrictionWithoutFacets() {
            // Нарушено: тип Code сужает xs:string, но внутри restriction нет ни одного фасета.
            assertViolation("strings/str-05-restriction-without-facets.xsd",
                    "<xs:restriction base=\"xs:string\"> (в <xs:simpleType name=\"Code\">)",
                    "не задан ни один из параметров xs:length, xs:maxLength, xs:enumeration, xs:pattern");
        }

        @Test
        @DisplayName("Только xs:minLength")
        void str_06_restrictionMinLengthOnly() {
            // Нарушено: тип Code ограничен только xs:minLength.
            assertViolation("strings/str-06-restriction-min-length-only.xsd",
                    "<xs:restriction base=\"xs:string\"> (в <xs:simpleType name=\"Code\">)",
                    "не задан ни один из параметров xs:length, xs:maxLength, xs:enumeration, xs:pattern");
        }

        @Test
        @DisplayName("Анонимный тип: только xs:whiteSpace")
        void str_07_restrictionWhiteSpaceOnly() {
            // Нарушено: у элемента note анонимный тип: restriction от xs:string только с xs:whiteSpace.
            assertViolation("strings/str-07-restriction-white-space-only.xsd",
                    "<xs:restriction base=\"xs:string\"> (в <xs:element name=\"note\">)",
                    "не задан ни один из параметров xs:length, xs:maxLength, xs:enumeration, xs:pattern");
        }

        @Test
        @DisplayName("simpleContent + extension base=\"xs:string\"")
        void str_08_simpleContentExtensionString() {
            // Нарушено: текст элемента note задан через extension от xs:string.
            assertViolation("strings/str-08-simple-content-extension-string.xsd",
                    "<xs:extension> base=\"xs:string\" (в <xs:complexType name=\"Note\">)",
                    "для строкового типа должно быть задано ограничение");
        }

        @Test
        @DisplayName("xs:list itemType=\"xs:string\"")
        void str_09_listOfString() {
            // Нарушено: список Tags состоит из элементов типа xs:string.
            assertViolation("strings/str-09-list-of-string.xsd",
                    "<xs:list> itemType=\"xs:string\" (в <xs:simpleType name=\"Tags\">)",
                    "для строкового типа должно быть задано ограничение");
        }

        @Test
        @DisplayName("xs:union с ветвью xs:string")
        void str_10_unionWithString() {
            // Нарушено: объединение DateOrText: xs:date или xs:string.
            assertViolation("strings/str-10-union-with-string.xsd",
                    "<xs:union> memberTypes=\"xs:string\" (в <xs:simpleType name=\"DateOrText\">)",
                    "для строкового типа должно быть задано ограничение");
        }

        @Test
        @DisplayName("xs:string внутри неиспользуемой xs:group")
        void str_11_stringInGroup() {
            // Нарушено: в именованной группе Contact элемент email объявлен с type="xs:string".
            assertViolation("strings/str-11-string-in-group.xsd",
                    "<xs:element name=\"email\"> type=\"xs:string\"",
                    "для строкового типа должно быть задано ограничение");
        }

        @Test
        @DisplayName("xs:string внутри xs:attributeGroup")
        void str_12_stringInAttributeGroup() {
            // Нарушено: в группе атрибутов Audit атрибут createdBy объявлен с type="xs:string".
            assertViolation("strings/str-12-string-in-attribute-group.xsd",
                    "<xs:attribute name=\"createdBy\"> type=\"xs:string\"",
                    "для строкового типа должно быть задано ограничение");
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("Ограничение чисел: totalDigits или пара min + max")
    class Numbers {

        @Test
        @DisplayName("Элемент с type=\"xs:decimal\"")
        void num_01_elementTypeDecimal() {
            // Нарушено: элемент value объявлен с type="xs:decimal".
            assertViolation("numbers/num-01-element-type-decimal.xsd",
                    "<xs:element name=\"value\"> type=\"xs:decimal\"",
                    "для числового типа должно быть задано ограничение");
        }

        @Test
        @DisplayName("Элемент с type=\"xs:integer\"")
        void num_02_elementTypeInteger() {
            // Нарушено: элемент value объявлен с type="xs:integer".
            assertViolation("numbers/num-02-element-type-integer.xsd",
                    "<xs:element name=\"value\"> type=\"xs:integer\"",
                    "для числового типа должно быть задано ограничение");
        }

        @Test
        @DisplayName("Элемент с type=\"xs:positiveInteger\"")
        void num_03_elementTypePositiveInteger() {
            // Нарушено: элемент value объявлен с type="xs:positiveInteger".
            assertViolation("numbers/num-03-element-type-positive-integer.xsd",
                    "<xs:element name=\"value\"> type=\"xs:positiveInteger\"",
                    "для числового типа должно быть задано ограничение");
        }

        @Test
        @DisplayName("Элемент с type=\"xs:nonNegativeInteger\"")
        void num_04_elementTypeNonNegativeInteger() {
            // Нарушено: элемент value объявлен с type="xs:nonNegativeInteger".
            assertViolation("numbers/num-04-element-type-non-negative-integer.xsd",
                    "<xs:element name=\"value\"> type=\"xs:nonNegativeInteger\"",
                    "для числового типа должно быть задано ограничение");
        }

        @Test
        @DisplayName("Элемент с type=\"xs:negativeInteger\"")
        void num_05_elementTypeNegativeInteger() {
            // Нарушено: элемент value объявлен с type="xs:negativeInteger".
            assertViolation("numbers/num-05-element-type-negative-integer.xsd",
                    "<xs:element name=\"value\"> type=\"xs:negativeInteger\"",
                    "для числового типа должно быть задано ограничение");
        }

        @Test
        @DisplayName("Элемент с type=\"xs:nonPositiveInteger\"")
        void num_06_elementTypeNonPositiveInteger() {
            // Нарушено: элемент value объявлен с type="xs:nonPositiveInteger".
            assertViolation("numbers/num-06-element-type-non-positive-integer.xsd",
                    "<xs:element name=\"value\"> type=\"xs:nonPositiveInteger\"",
                    "для числового типа должно быть задано ограничение");
        }

        @Test
        @DisplayName("restriction base=\"xs:decimal\" без фасетов")
        void num_07_restrictionWithoutFacets() {
            // Нарушено: тип Amount сужает xs:decimal, но restriction пустой.
            assertViolation("numbers/num-07-restriction-without-facets.xsd",
                    "<xs:restriction base=\"xs:decimal\"> (в <xs:simpleType name=\"Amount\">)",
                    "должен быть задан xs:totalDigits либо пара");
        }

        @Test
        @DisplayName("Только xs:fractionDigits")
        void num_08_fractionDigitsOnly() {
            // Нарушено: тип Amount ограничен только xs:fractionDigits.
            assertViolation("numbers/num-08-fraction-digits-only.xsd",
                    "<xs:restriction base=\"xs:decimal\"> (в <xs:simpleType name=\"Amount\">)",
                    "должен быть задан xs:totalDigits либо пара");
        }

        @Test
        @DisplayName("Только нижняя граница")
        void num_09_minBoundOnly() {
            // Нарушено: тип Quantity ограничен только xs:minInclusive.
            assertViolation("numbers/num-09-min-bound-only.xsd",
                    "<xs:restriction base=\"xs:integer\"> (в <xs:simpleType name=\"Quantity\">)",
                    "должен быть задан xs:totalDigits либо пара");
        }

        @Test
        @DisplayName("Только верхняя граница")
        void num_10_maxBoundOnly() {
            // Нарушено: тип Delta ограничен только xs:maxExclusive.
            assertViolation("numbers/num-10-max-bound-only.xsd",
                    "<xs:restriction base=\"xs:integer\"> (в <xs:simpleType name=\"Delta\">)",
                    "должен быть задан xs:totalDigits либо пара");
        }

        @Test
        @DisplayName("xs:positiveInteger только с верхней границей")
        void num_11_positiveIntegerMaxOnly() {
            // Нарушено: тип Count на основе xs:positiveInteger ограничен только xs:maxInclusive.
            assertViolation("numbers/num-11-positive-integer-max-only.xsd",
                    "<xs:restriction base=\"xs:positiveInteger\"> (в <xs:simpleType name=\"Count\">)",
                    "должен быть задан xs:totalDigits либо пара");
        }

        @Test
        @DisplayName("xs:list itemType=\"xs:integer\"")
        void num_12_listOfInteger() {
            // Нарушено: список Ids состоит из элементов типа xs:integer.
            assertViolation("numbers/num-12-list-of-integer.xsd",
                    "<xs:list> itemType=\"xs:integer\" (в <xs:simpleType name=\"Ids\">)",
                    "для числового типа должно быть задано ограничение");
        }

        @Test
        @DisplayName("simpleContent + extension base=\"xs:decimal\"")
        void num_13_simpleContentExtensionDecimal() {
            // Нарушено: текст элемента price задан через extension от xs:decimal.
            assertViolation("numbers/num-13-simple-content-extension-decimal.xsd",
                    "<xs:extension> base=\"xs:decimal\" (в <xs:complexType name=\"Money\">)",
                    "для числового типа должно быть задано ограничение");
        }

        @Test
        @DisplayName("Атрибут с type=\"xs:integer\"")
        void num_14_attributeTypeInteger() {
            // Нарушено: атрибут version объявлен с type="xs:integer".
            assertViolation("numbers/num-14-attribute-type-integer.xsd",
                    "<xs:attribute name=\"version\"> type=\"xs:integer\"",
                    "для числового типа должно быть задано ограничение");
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("Запрет всеядных конструкций: xs:any, xs:anyType, xs:anySimpleType")
    class AnyConstructs {

        @Test
        @DisplayName("xs:any")
        void any_01_any() {
            // Нарушено: в модели содержимого элемента extra стоит xs:any.
            assertViolation("any/any-01-any.xsd",
                    "использование xs:any запрещено (в <xs:element name=\"extra\">)");
        }

        @Test
        @DisplayName("xs:any внутри xs:choice в xs:group")
        void any_02_anyInChoiceInGroup() {
            // Нарушено: xs:any вложен глубоко: в xs:choice внутри именованной группы Payload.
            assertViolation("any/any-02-any-in-choice-in-group.xsd",
                    "использование xs:any запрещено (в <xs:group name=\"Payload\">)");
        }

        @Test
        @DisplayName("Элемент с type=\"xs:anyType\"")
        void any_03_elementTypeAnyType() {
            // Нарушено: элемент raw объявлен с type="xs:anyType".
            assertViolation("any/any-03-element-type-any-type.xsd",
                    "<xs:element name=\"raw\"> type=\"xs:anyType\"",
                    "использование xs:anyType запрещено");
        }

        @Test
        @DisplayName("Элемент без типа (неявный xs:anyType)")
        void any_04_elementWithoutType() {
            // Нарушено: элемент untyped объявлен без type и без вложенного типа.
            assertViolation("any/any-04-element-without-type.xsd",
                    "элемент 'untyped' объявлен без типа и неявно имеет тип xs:anyType");
        }

        @Test
        @DisplayName("Локальный элемент без типа")
        void any_05_localElementWithoutType() {
            // Нарушено: вложенный элемент data внутри complexType объявлен без типа.
            assertViolation("any/any-05-local-element-without-type.xsd",
                    "элемент 'data' объявлен без типа и неявно имеет тип xs:anyType");
        }

        @Test
        @DisplayName("Элемент только с xs:annotation")
        void any_06_elementWithAnnotationOnly() {
            // Нарушено: у элемента described есть только документация (xs:annotation), типа нет.
            assertViolation("any/any-06-element-with-annotation-only.xsd",
                    "элемент 'described' объявлен без типа и неявно имеет тип xs:anyType");
        }

        @Test
        @DisplayName("Атрибут с type=\"xs:anySimpleType\"")
        void any_07_attributeTypeAnySimpleType() {
            // Нарушено: атрибут flag объявлен с type="xs:anySimpleType".
            assertViolation("any/any-07-attribute-type-any-simple-type.xsd",
                    "<xs:attribute name=\"flag\"> type=\"xs:anySimpleType\"",
                    "использование xs:anySimpleType запрещено");
        }

        @Test
        @DisplayName("Атрибут без типа (неявный xs:anySimpleType)")
        void any_08_attributeWithoutType() {
            // Нарушено: атрибут flag объявлен без type и без вложенного simpleType.
            assertViolation("any/any-08-attribute-without-type.xsd",
                    "атрибут 'flag' объявлен без типа и неявно имеет тип xs:anySimpleType");
        }

        @Test
        @DisplayName("xs:list itemType=\"xs:anySimpleType\"")
        void any_09_listOfAnySimpleType() {
            // Нарушено: список Values состоит из элементов типа xs:anySimpleType.
            assertViolation("any/any-09-list-of-any-simple-type.xsd",
                    "<xs:list> itemType=\"xs:anySimpleType\" (в <xs:simpleType name=\"Values\">)",
                    "использование xs:anySimpleType запрещено");
        }

        @Test
        @DisplayName("complexContent + extension base=\"xs:anyType\"")
        void any_10_complexContentExtensionAnyType() {
            // Нарушено: тип Payload расширяет xs:anyType.
            assertViolation("any/any-10-complex-content-extension-any-type.xsd",
                    "<xs:extension> base=\"xs:anyType\" (в <xs:complexType name=\"Payload\">)",
                    "использование xs:anyType запрещено");
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("Префиксы: проверка идёт по пространству имён, а не по тексту «xs:»")
    class Namespaces {

        @Test
        @DisplayName("Префикс xsd: вместо xs:")
        void ns_01_xsdPrefix() {
            // Нарушено: type="xsd:string" — тот же xs:string под другим префиксом.
            assertViolation("namespaces/ns-01-xsd-prefix.xsd",
                    "<xs:element name=\"name\"> type=\"xsd:string\"",
                    "для строкового типа должно быть задано ограничение");
        }

        @Test
        @DisplayName("XML Schema как пространство имён по умолчанию")
        void ns_02_defaultNamespace() {
            // Нарушено: type="decimal" без префикса — это xs:decimal.
            assertViolation("namespaces/ns-02-default-namespace.xsd",
                    "<xs:element name=\"amount\"> type=\"decimal\"",
                    "для числового типа должно быть задано ограничение");
        }

        @Test
        @DisplayName("Свой тип с именем string в своём пространстве имён")
        void ns_03_userTypeNamedString() {
            // Допустимо: type="string" ссылается на наш ограниченный тип, а не на xs:string.
            assertValid("namespaces/ns-03-user-type-named-string.xsd");
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("Несколько файлов: проверяются все файлы схемы")
    class MultiFile {

        @Test
        @DisplayName("Итоговая схема заказа из трёх файлов проходит")
        void mf_01_validSchemaOfThreeFiles() {
            // Допустимо: order.xsd + address.xsd (include) + common.xsd (import), все правила соблюдены.
            assertValid("multifile/valid/order.xsd", "multifile/valid/address.xsd", "multifile/valid/common.xsd");
        }

        @Test
        @DisplayName("Нарушение в импортируемом файле")
        void mf_02_violationInImportedFile() {
            // Нарушено: главный файл чистый, в импортируемом types.xsd — неограниченная строка.
            assertViolationIn("multifile/import-violation/types.xsd",
                    new String[]{"multifile/import-violation/main.xsd", "multifile/import-violation/types.xsd"},
                    "<xs:element name=\"note\"> type=\"xs:string\"");
        }

        @Test
        @DisplayName("Нарушение во включаемом файле")
        void mf_03_violationInIncludedFile() {
            // Нарушено: главный файл чистый, во включаемом part.xsd — число только с fractionDigits.
            assertViolationIn("multifile/include-violation/part.xsd",
                    new String[]{"multifile/include-violation/main.xsd", "multifile/include-violation/part.xsd"},
                    "<xs:restriction base=\"xs:decimal\"> (в <xs:simpleType name=\"Amount\">)");
        }

        @Test
        @DisplayName("Главный файл проверяется первым")
        void mf_04_mainFileIsCheckedFirst() {
            // Нарушения в обоих файлах; сообщение должно быть про главный (элемент без типа).
            assertViolationIn("multifile/main-first/main.xsd",
                    new String[]{"multifile/main-first/main.xsd", "multifile/main-first/part.xsd"},
                    "элемент 'untyped' объявлен без типа");
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("Общее поведение")
    class General {

        @Test
        @DisplayName("Останавливается на первом нарушении в файле (сверху вниз)")
        void g_01_firstViolationWins() {
            // В файле xs:any, а ниже xs:string; в сообщении должно быть только первое.
            XsdValidationException e = assertThrows(XsdValidationException.class,
                    () -> VALIDATOR.validate(load("general/g01-first-violation-wins.xsd")));
            assertTrue(e.getMessage().contains("использование xs:any запрещено"), e.getMessage());
            assertFalse(e.getMessage().contains("xs:string"), e.getMessage());
        }

        @Test
        @DisplayName("null вместо схемы — NullPointerException")
        void g_02_nullSchema() {
            assertThrows(NullPointerException.class, () -> VALIDATOR.validate(null));
        }
    }

    // ================================================================== helpers

    /** Схема из перечисленных файлов (главный первым) должна пройти валидацию. */
    private static void assertValid(String... files) {
        XsdSchema schema = load(files);
        try {
            VALIDATOR.validate(schema);
        } catch (XsdValidationException e) {
            fail("Ожидалось, что схема пройдёт валидацию, но: " + e.getMessage());
        }
    }

    /**
     * Однофайловая схема должна нарушать правило: сообщение начинается с имени файла
     * и содержит все фрагменты.
     */
    private static void assertViolation(String file, String... fragments) {
        assertViolationIn(file, new String[]{file}, fragments);
    }

    /** Многофайловая схема должна нарушать правило именно в файле {@code expectedFile}. */
    private static void assertViolationIn(String expectedFile, String[] files, String... fragments) {
        XsdSchema schema = load(files);
        XsdValidationException e = assertThrows(XsdValidationException.class, () -> VALIDATOR.validate(schema),
                "Ожидалось нарушение в " + expectedFile);
        String message = e.getMessage();
        assertTrue(message.startsWith("[" + BASE + expectedFile + "]"),
                "Нарушение должно быть в файле " + expectedFile + ", а сообщение: " + message);
        for (String fragment : fragments) {
            assertTrue(message.contains(fragment),
                    "В сообщении нет фрагмента «" + fragment + "»: " + message);
        }
    }

    /**
     * Собирает {@link XsdSchema} так же, как это делает XsdSchemaCreator, но без него:
     * главный файл компилируется SchemaFactory (xs:include / xs:import разрешаются относительно него),
     * содержимое всех перечисленных файлов кладётся в мапу в том же порядке.
     */
    private static XsdSchema load(String... files) {
        Map<String, byte[]> content = new LinkedHashMap<>();
        for (String file : files) {
            content.put(BASE + file, read(resource(file)));
        }
        return new XsdSchema(compile(files[0]), content);
    }

    private static Schema compile(String file) {
        try {
            return SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(resource(file));
        } catch (SAXException e) {
            throw new IllegalStateException("Тестовая схема не компилируется (ошибка в самом тесте): " + file, e);
        }
    }

    private static URL resource(String file) {
        URL url = XsdFacetValidatorTest.class.getClassLoader().getResource(BASE + file);
        assertNotNull(url, "Тестовая схема не найдена в classpath: " + BASE + file);
        return url;
    }

    private static byte[] read(URL url) {
        try (InputStream in = url.openStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
