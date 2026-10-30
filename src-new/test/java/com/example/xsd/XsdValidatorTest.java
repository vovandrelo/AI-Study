package com.example.xsd;

import com.example.xsd.XsdValidator.Stage;
import com.example.xsd.XsdValidator.ValidationResult;
import com.example.xsd.XsdValidator.Violation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты {@link XsdValidator}.
 *
 * <p>Тестовые схемы лежат в src/test/resources/xsd/. В каждой из них нарушения помечены
 * комментарием "НАРУШЕНИЕ (правило N)", а имена нарушителей начинаются с префикса r1_/r2_/r3_.
 * Объявления с префиксом ok_ правила соблюдают, поэтому их имён в сообщениях быть не должно.
 *
 * <p>Правила:
 * <ul>
 *   <li>Этап 1 — XSD компилируется через SchemaFactory#newSchema без ошибок.</li>
 *   <li>Правило 1 — xs:string ограничен xs:length / xs:maxLength / xs:enumeration / xs:pattern.</li>
 *   <li>Правило 2 — xs:decimal, xs:integer, xs:negativeInteger, xs:nonNegativeInteger,
 *       xs:nonPositiveInteger, xs:positiveInteger ограничены xs:totalDigits
 *       или парой xs:min(Inclusive|Exclusive) + xs:max(Inclusive|Exclusive).</li>
 *   <li>Правило 3 — нет xs:any, xs:anyType, xs:anySimpleType (в том числе неявных).</li>
 * </ul>
 */
@DisplayName("XsdValidator")
class XsdValidatorTest {

    /** Валидатор потокобезопасен, поэтому один экземпляр на все тесты. */
    private static final XsdValidator VALIDATOR = new XsdValidator();

    // =====================================================================================
    @Nested
    @DisplayName("Валидная схема")
    class ValidSchema {

        @Test
        @DisplayName("valid.xsd: все разрешённые варианты по всем правилам — ни одного нарушения")
        void validSchemaPasses() {
            ValidationResult result = validate("valid.xsd");

            assertTrue(result.isValid(), "Ожидалась валидная схема, но найдены нарушения:\n" + dump(result));
            assertNull(result.failedStage(), "У валидной схемы не должно быть этапа с ошибкой");
            assertTrue(result.violations().isEmpty());
        }

        @Test
        @DisplayName("Вход через InputStream и через byte[] даёт одинаковый результат")
        void inputStreamAndByteArrayGiveSameResult() throws IOException {
            byte[] bytes = readResource("invalid-rule1-string.xsd");
            ValidationResult fromBytes = VALIDATOR.validate(bytes);
            ValidationResult fromStream;
            try (InputStream in = resource("invalid-rule1-string.xsd")) {
                fromStream = VALIDATOR.validate(in);
            }
            assertEquals(fromBytes, fromStream);
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("Этап 1: стандартная валидация — компиляция схемы через SchemaFactory")
    class StandardValidation {

        private final ValidationResult result = validate("invalid-structure.xsd");

        @Test
        @DisplayName("Схема с синтаксическими ошибками XSD падает на этапе STANDARD")
        void failsOnStandardStage() {
            assertFalse(result.isValid());
            assertEquals(Stage.STANDARD, result.failedStage());
        }

        @Test
        @DisplayName("Этап 1: у xs:element недопустимый атрибут foo=\"bar\"")
        void unknownAttribute() {
            assertViolation(result, "Этап 1: недопустимый атрибут 'foo' у xs:element", "'foo'");
        }

        @Test
        @DisplayName("Этап 1: тег xs:elemen (опечатка) не существует в XML Schema")
        void misspelledTag() {
            assertViolation(result, "Этап 1: несуществующий тег xs:elemen", "starting at: elemen.");
        }

        @Test
        @DisplayName("Этап 1: у глобального xs:element не задан обязательный атрибут name")
        void missingRequiredName() {
            assertViolation(result, "Этап 1: у глобального xs:element нет атрибута name", "'name'");
        }

        @Test
        @DisplayName("Если этап 1 не пройден, кастомные проверки (этап 2) не выполняются")
        void customStageIsSkipped() {
            assertNoViolationMentions(result, "customViolationNotReported");
            assertNoViolationMentions(result, "строкового типа");
        }

        @Test
        @DisplayName("Этап 1: некорректный XML (незакрытый тег) — ошибка разбора")
        void notWellFormedXml() {
            ValidationResult notXml = validate("invalid-not-well-formed.xsd");

            assertEquals(Stage.STANDARD, notXml.failedStage());
            assertEquals(1, notXml.violations().size(), "Ожидалась одна фатальная ошибка:\n" + dump(notXml));
            assertTrue(notXml.violations().get(0).line() > 0, "Должна быть указана строка ошибки");
        }

        @Test
        @DisplayName("Этап 1: пустой вход — ошибка разбора")
        void emptyInput() {
            ValidationResult empty = VALIDATOR.validate(new byte[0]);

            assertEquals(Stage.STANDARD, empty.failedStage());
            assertFalse(empty.violations().isEmpty());
        }

        @Test
        @DisplayName("Этап 1: xs:import не загружается — внешний доступ запрещён")
        void importIsNotLoaded() {
            ValidationResult imported = validate("invalid-import.xsd");

            assertEquals(Stage.STANDARD, imported.failedStage(), dump(imported));
            assertViolation(imported, "Этап 1: импортируемую схему загрузить нельзя", "common.xsd");
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("Этап 1: семантические ошибки, которые находит только компилятор")
    class SemanticValidation {

        private final ValidationResult result = validate("invalid-semantic.xsd");

        @Test
        @DisplayName("Синтаксически верная, но некомпилируемая схема падает на этапе STANDARD")
        void failsOnStandardStage() {
            assertEquals(Stage.STANDARD, result.failedStage(), dump(result));
        }

        @Test
        @DisplayName("Этап 1: ссылка на необъявленный тип type=\"NoSuchType\"")
        void undefinedType() {
            assertViolation(result, "Этап 1: тип NoSuchType не объявлен", "'NoSuchType'");
        }

        @Test
        @DisplayName("Этап 1: фасет xs:maxLength неприменим к xs:decimal")
        void facetNotApplicable() {
            assertViolation(result, "Этап 1: xs:maxLength у числового типа", "'maxLength'", "MaxLengthOnDecimal");
        }

        @Test
        @DisplayName("Этап 1: restriction base=\"xs:anySimpleType\" запрещён стандартом")
        void restrictionOfAnySimpleType() {
            assertViolation(result, "Этап 1: наследование от xs:anySimpleType", "RestrictionOfAnySimpleType");
        }

        @Test
        @DisplayName("Если этап 1 не пройден, кастомные проверки (этап 2) не выполняются")
        void customStageIsSkipped() {
            assertNoViolationMentions(result, "customViolationNotReported");
        }

        @Test
        @DisplayName("Этап 1: найдено ровно 3 ошибки")
        void exactViolationCount() {
            assertEquals(3, result.violations().size(), dump(result));
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("Правило 1: xs:string ограничен xs:length / xs:maxLength / xs:enumeration / xs:pattern")
    class Rule1String {

        private final ValidationResult result = validate("invalid-rule1-string.xsd");

        @Test
        @DisplayName("Этап 1 пройден, ошибки найдены на этапе CUSTOM")
        void failsOnCustomStage() {
            assertEquals(Stage.CUSTOM, result.failedStage(), dump(result));
        }

        @Test
        @DisplayName("Правило 1: restriction base=\"xs:string\" без фасетов вообще")
        void restrictionWithoutFacets() {
            assertViolation(result, "Правило 1: restriction base=xs:string без фасетов",
                    "r1_RestrictionNoFacets", "xs:maxLength");
        }

        @Test
        @DisplayName("Правило 1: restriction base=\"xs:string\" только с xs:minLength (minLength не засчитывается)")
        void restrictionWithMinLengthOnly() {
            assertViolation(result, "Правило 1: xs:minLength не заменяет length/maxLength/enumeration/pattern",
                    "r1_RestrictionMinLengthOnly", "xs:maxLength");
        }

        @Test
        @DisplayName("Правило 1: вложенный restriction base=\"xs:string\" только с xs:whiteSpace")
        void inlineRestrictionWithWhiteSpaceOnly() {
            assertViolation(result, "Правило 1: вложенный restriction xs:string без ограничения длины/значений",
                    "r1_elementInlineRestriction", "xs:maxLength");
        }

        @Test
        @DisplayName("Правило 1: xs:element с type=\"xs:string\" напрямую")
        void elementWithDirectStringType() {
            assertViolation(result, "Правило 1: элемент с неограниченным type=xs:string",
                    "name=\"r1_elementTypeString\"", "type=\"xs:string\"");
        }

        @Test
        @DisplayName("Правило 1: xs:attribute с type=\"xs:string\" напрямую")
        void attributeWithDirectStringType() {
            assertViolation(result, "Правило 1: атрибут с неограниченным type=xs:string",
                    "name=\"r1_attributeTypeString\"", "type=\"xs:string\"");
        }

        @Test
        @DisplayName("Правило 1: simpleContent/extension base=\"xs:string\" — текст не ограничен")
        void extensionOfString() {
            assertViolation(result, "Правило 1: extension base=xs:string не ограничивает текст",
                    "r1_elementExtensionString", "base=\"xs:string\"");
        }

        @Test
        @DisplayName("Правило 1: xs:list itemType=\"xs:string\" — элементы списка не ограничены")
        void listOfString() {
            assertViolation(result, "Правило 1: список из неограниченных строк",
                    "r1_ListOfString", "itemType=\"xs:string\"");
        }

        @Test
        @DisplayName("Правило 1: xs:union memberTypes содержит xs:string")
        void unionWithString() {
            assertViolation(result, "Правило 1: ветвь union — неограниченная строка",
                    "r1_UnionWithString", "memberTypes=\"xs:string\"");
        }

        @Test
        @DisplayName("Правило 1: maxLength, length, enumeration, pattern и наследник ограниченного типа — не нарушения")
        void allowedVariantsAreNotReported() {
            assertNoViolationMentions(result, "ok_");
        }

        @Test
        @DisplayName("Правило 1: найдено ровно 8 нарушений — лишних срабатываний нет")
        void exactViolationCount() {
            assertEquals(8, result.violations().size(), dump(result));
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("Правило 2: числовые типы ограничены xs:totalDigits или парой min* + max*")
    class Rule2Numeric {

        private final ValidationResult result = validate("invalid-rule2-numeric.xsd");

        @Test
        @DisplayName("Этап 1 пройден, ошибки найдены на этапе CUSTOM")
        void failsOnCustomStage() {
            assertEquals(Stage.CUSTOM, result.failedStage(), dump(result));
        }

        @Test
        @DisplayName("Правило 2: type=\"xs:decimal\" напрямую")
        void elementDecimal() {
            assertViolation(result, "Правило 2: неограниченный xs:decimal",
                    "name=\"r2_elementDecimal\"", "type=\"xs:decimal\"");
        }

        @Test
        @DisplayName("Правило 2: type=\"xs:integer\" напрямую")
        void elementInteger() {
            assertViolation(result, "Правило 2: неограниченный xs:integer",
                    "name=\"r2_elementInteger\"", "type=\"xs:integer\"");
        }

        @Test
        @DisplayName("Правило 2: type=\"xs:negativeInteger\" напрямую")
        void elementNegativeInteger() {
            assertViolation(result, "Правило 2: неограниченный xs:negativeInteger",
                    "name=\"r2_elementNegativeInteger\"", "type=\"xs:negativeInteger\"");
        }

        @Test
        @DisplayName("Правило 2: type=\"xs:nonNegativeInteger\" напрямую")
        void elementNonNegativeInteger() {
            assertViolation(result, "Правило 2: неограниченный xs:nonNegativeInteger",
                    "name=\"r2_elementNonNegativeInteger\"", "type=\"xs:nonNegativeInteger\"");
        }

        @Test
        @DisplayName("Правило 2: type=\"xs:nonPositiveInteger\" напрямую")
        void elementNonPositiveInteger() {
            assertViolation(result, "Правило 2: неограниченный xs:nonPositiveInteger",
                    "name=\"r2_elementNonPositiveInteger\"", "type=\"xs:nonPositiveInteger\"");
        }

        @Test
        @DisplayName("Правило 2: type=\"xs:positiveInteger\" напрямую")
        void elementPositiveInteger() {
            assertViolation(result, "Правило 2: неограниченный xs:positiveInteger",
                    "name=\"r2_elementPositiveInteger\"", "type=\"xs:positiveInteger\"");
        }

        @Test
        @DisplayName("Правило 2: xs:attribute с type=\"xs:integer\" напрямую")
        void attributeInteger() {
            assertViolation(result, "Правило 2: атрибут с неограниченным xs:integer",
                    "name=\"r2_attributeInteger\"", "type=\"xs:integer\"");
        }

        @Test
        @DisplayName("Правило 2: restriction base=\"xs:decimal\" без фасетов")
        void restrictionWithoutFacets() {
            assertViolation(result, "Правило 2: restriction без totalDigits и без границ",
                    "r2_DecimalNoFacets", "xs:totalDigits");
        }

        @Test
        @DisplayName("Правило 2: только xs:fractionDigits — это не xs:totalDigits")
        void fractionDigitsOnly() {
            assertViolation(result, "Правило 2: xs:fractionDigits не заменяет xs:totalDigits",
                    "r2_DecimalFractionDigitsOnly", "xs:totalDigits");
        }

        @Test
        @DisplayName("Правило 2: только нижняя граница (xs:minInclusive) без верхней")
        void minBoundOnly() {
            assertViolation(result, "Правило 2: есть min*, но нет max*",
                    "r2_IntegerMinOnly", "xs:maxInclusive");
        }

        @Test
        @DisplayName("Правило 2: только верхняя граница (xs:maxExclusive) без нижней")
        void maxBoundOnly() {
            assertViolation(result, "Правило 2: есть max*, но нет min*",
                    "r2_IntegerMaxOnly", "xs:minInclusive");
        }

        @Test
        @DisplayName("Правило 2: вложенный restriction xs:nonNegativeInteger только с xs:maxInclusive")
        void inlineRestrictionMaxOnly() {
            assertViolation(result, "Правило 2: вложенный restriction без пары границ",
                    "r2_elementInlineMaxOnly", "xs:totalDigits");
        }

        @Test
        @DisplayName("Правило 2: simpleContent/extension base=\"xs:decimal\" — число не ограничено")
        void extensionOfDecimal() {
            assertViolation(result, "Правило 2: extension base=xs:decimal не ограничивает значение",
                    "r2_elementExtensionDecimal", "base=\"xs:decimal\"");
        }

        @Test
        @DisplayName("Правило 2: xs:list itemType=\"xs:integer\" — элементы списка не ограничены")
        void listOfInteger() {
            assertViolation(result, "Правило 2: список из неограниченных чисел",
                    "r2_ListOfInteger", "itemType=\"xs:integer\"");
        }

        @Test
        @DisplayName("Правило 2: totalDigits, все комбинации min*/max*, наследник, xs:int и xs:long — не нарушения")
        void allowedVariantsAreNotReported() {
            assertNoViolationMentions(result, "ok_");
        }

        @Test
        @DisplayName("Правило 2: найдено ровно 14 нарушений — лишних срабатываний нет")
        void exactViolationCount() {
            assertEquals(14, result.violations().size(), dump(result));
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("Правило 3: нет xs:any, xs:anyType, xs:anySimpleType")
    class Rule3Any {

        private final ValidationResult result = validate("invalid-rule3-any.xsd");

        @Test
        @DisplayName("Этап 1 пройден, ошибки найдены на этапе CUSTOM")
        void failsOnCustomStage() {
            assertEquals(Stage.CUSTOM, result.failedStage(), dump(result));
        }

        @Test
        @DisplayName("Правило 3: тег xs:any")
        void anyElement() {
            assertViolation(result, "Правило 3: использован xs:any",
                    "xs:any запрещено", "r3_elementWithAny");
        }

        @Test
        @DisplayName("Правило 3: xs:element с явным type=\"xs:anyType\"")
        void explicitAnyType() {
            assertViolation(result, "Правило 3: явный xs:anyType",
                    "name=\"r3_elementTypeAnyType\"", "xs:anyType запрещено");
        }

        @Test
        @DisplayName("Правило 3: xs:element без type и без вложенного типа — неявный xs:anyType")
        void implicitAnyType() {
            assertViolation(result, "Правило 3: неявный xs:anyType у элемента без типа",
                    "'r3_elementImplicitAnyType'", "xs:anyType");
        }

        @Test
        @DisplayName("Правило 3: xs:attribute с явным type=\"xs:anySimpleType\"")
        void explicitAnySimpleType() {
            assertViolation(result, "Правило 3: явный xs:anySimpleType",
                    "name=\"r3_attributeAnySimpleType\"", "xs:anySimpleType запрещено");
        }

        @Test
        @DisplayName("Правило 3: xs:attribute без type — неявный xs:anySimpleType")
        void implicitAnySimpleType() {
            assertViolation(result, "Правило 3: неявный xs:anySimpleType у атрибута без типа",
                    "'r3_attributeImplicitAnySimpleType'", "xs:anySimpleType");
        }

        @Test
        @DisplayName("Правило 3: xs:list itemType=\"xs:anySimpleType\"")
        void listOfAnySimpleType() {
            assertViolation(result, "Правило 3: список из xs:anySimpleType",
                    "r3_ListOfAnySimpleType", "xs:anySimpleType запрещено");
        }

        @Test
        @DisplayName("Правило 3: complexContent/extension base=\"xs:anyType\"")
        void extensionOfAnyType() {
            assertViolation(result, "Правило 3: расширение xs:anyType",
                    "r3_ExtensionOfAnyType", "xs:anyType запрещено");
        }

        @Test
        @DisplayName("Правило 3: complexContent/restriction base=\"xs:anyType\", ref и substitutionGroup — не нарушения")
        void allowedVariantsAreNotReported() {
            assertNoViolationMentions(result, "ok_");
            assertNoViolationMentions(result, "''"); // нарушение без имени означало бы ложное срабатывание на ref
        }

        @Test
        @DisplayName("Правило 3: найдено ровно 7 нарушений — лишних срабатываний нет")
        void exactViolationCount() {
            assertEquals(7, result.violations().size(), dump(result));
        }
    }

    // =====================================================================================
    @Nested
    @DisplayName("Префиксы: проверки работают по namespace, а не по тексту \"xs:\"")
    class NamespacePrefixes {

        @Test
        @DisplayName("Префикс xsd: — нарушения правил 1, 2 и 3 найдены")
        void xsdPrefix() {
            ValidationResult result = validate("invalid-xsd-prefix.xsd");

            assertEquals(Stage.CUSTOM, result.failedStage(), dump(result));
            assertViolation(result, "Правило 1 с префиксом xsd:", "r1_xsdString", "type=\"xsd:string\"");
            assertViolation(result, "Правило 2 с префиксом xsd:", "r2_xsdDecimal", "type=\"xsd:decimal\"");
            assertViolation(result, "Правило 3 с префиксом xsd:", "r3_xsdAny", "xs:any запрещено");
            assertEquals(3, result.violations().size(), dump(result));
        }

        @Test
        @DisplayName("Пользовательский тип с именем \"string\" в своём namespace — не xs:string")
        void userTypeNamedString() {
            ValidationResult result = validate("invalid-xsd-prefix.xsd");

            assertNoViolationMentions(result, "ok_userTypeNamedString");
        }

        @Test
        @DisplayName("XML Schema как namespace по умолчанию (type=\"string\" без префикса) — нарушения найдены")
        void defaultNamespace() {
            ValidationResult result = validate("invalid-default-namespace.xsd");

            assertEquals(Stage.CUSTOM, result.failedStage(), dump(result));
            assertViolation(result, "Правило 1 без префикса", "r1_defaultNsString", "type=\"string\"");
            assertViolation(result, "Правило 2 без префикса", "r2_defaultNsInteger", "type=\"integer\"");
            assertEquals(2, result.violations().size(), dump(result));
        }
    }

    // ================================================================== helpers

    /**
     * Проверяет, что среди нарушений ровно одно содержит все переданные фрагменты
     * и у него указан номер строки.
     *
     * @param rule      какое правило нарушено — попадёт в сообщение, если проверка упадёт
     * @param fragments фрагменты текста сообщения, однозначно определяющие нарушение
     */
    private static void assertViolation(ValidationResult result, String rule, String... fragments) {
        List<Violation> matches = result.violations().stream()
                .filter(v -> Arrays.stream(fragments).allMatch(v.message()::contains))
                .toList();

        assertEquals(1, matches.size(),
                "Ожидалось ровно одно нарушение [" + rule + "] с фрагментами " + Arrays.toString(fragments)
                        + ", найдено: " + matches.size() + "\nВсе нарушения:\n" + dump(result));
        assertTrue(matches.get(0).line() > 0,
                "У нарушения [" + rule + "] должен быть номер строки: " + matches.get(0));
    }

    /** Проверяет, что ни одно сообщение не содержит фрагмент (нет ложных срабатываний). */
    private static void assertNoViolationMentions(ValidationResult result, String fragment) {
        List<Violation> unexpected = result.violations().stream()
                .filter(v -> v.message().contains(fragment))
                .toList();
        assertTrue(unexpected.isEmpty(),
                "Ложное срабатывание: нарушения не ожидались, но найдены (фрагмент '" + fragment + "'):\n"
                        + unexpected.stream().map(Violation::toString).collect(Collectors.joining("\n")));
    }

    private static ValidationResult validate(String fileName) {
        try (InputStream in = resource(fileName)) {
            return VALIDATOR.validate(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] readResource(String fileName) throws IOException {
        try (InputStream in = resource(fileName)) {
            return in.readAllBytes();
        }
    }

    private static InputStream resource(String fileName) {
        InputStream in = XsdValidatorTest.class.getResourceAsStream("/xsd/" + fileName);
        assertNotNull(in, "Тестовая схема не найдена в classpath: /xsd/" + fileName);
        return in;
    }

    private static String dump(ValidationResult result) {
        return result.violations().stream().map(v -> "  " + v).collect(Collectors.joining("\n"));
    }
}
