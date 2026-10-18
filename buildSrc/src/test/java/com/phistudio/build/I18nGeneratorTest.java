package com.phistudio.build;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class I18nGeneratorTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Path input;
    @Before public void setup() throws Exception { input = temp.newFolder("languages").toPath(); }
    private void write(String tag, String contents) throws Exception {
        Files.writeString(input.resolve(tag + ".json"), contents, StandardCharsets.UTF_8);
    }
    private String pack(String strings) { return "{\"meta\":{\"name\":\"Language\"},\"strings\":{" + strings + "}}"; }
    private void rejects(String message) throws Exception {
        try { new I18nGenerator(input); fail("Expected rejection: " + message); }
        catch (IllegalArgumentException error) { assertTrue(error.getMessage(), error.getMessage().contains(message)); }
    }
    @Test public void duplicatesAndNonStrictJsonAreRejected() throws Exception {
        write("en",pack("\"key\":\"a\",\"key\":\"b\"")); rejects("duplicate key");
        write("en","{\"meta\":{\"name\":\"English\"}, /*comment*/ \"strings\":{}}"); rejects("en.json");
        write("en",pack("\"key\":\"a\"")+" {}"); rejects("en.json");
    }
    @Test public void unknownKeysAndOptionsFailInsteadOfSilentlyFallingBack() throws Exception {
        write("en",pack("\"key\":\"%1$s %2$d\""));
        write("zh-CN",pack("\"typo\":\"value\"")); rejects("unknown strings key");
        write("zh-CN",pack("\"key\":\"%1$d %2$s\"")); rejects("format arguments differ");
        write("zh-CN","{\"meta\":{\"name\":\"中文\"},\"options\":{}}"); rejects("only en.json");
        Files.delete(input.resolve("zh-CN.json"));
        write("en","{\"meta\":{\"name\":\"English\"},\"strings\":{\"key\":\"x\"},\"options\":{\"key\":{\"typo\":false}}}");
        rejects("unknown field");
    }
    @Test public void reorderedRepeatedArgumentsAndEscapedPercentWork() throws Exception {
        write("en",pack("\"key\":\"%1$s %2$d %%\""));
        write("zh-CN",pack("\"key\":\"%2$d %1$s %1$s %%\""));
        new I18nGenerator(input).generate(temp.newFolder("output").toPath());
        assertEquals(Map.of(1,"s",2,"integer:d"),I18nGenerator.contract("%s %d %%"));
    }
    @Test public void invalidFlagsAndChangedNumericMeaningAreRejected() throws Exception {
        write("en",pack("\"key\":\"%1$d\""));
        write("zh-CN",pack("\"key\":\"%1$x\""));rejects("format arguments differ");
        write("zh-CN",pack("\"key\":\"%1$--d\""));rejects("invalid format");
        write("zh-CN",pack("\"key\":\"%1$2000000000d\""));rejects("exceeds limit");
    }
    @Test public void partialPacksAndDifferentPluralRulesUseNativeFallback() throws Exception {
        write("en","{\"meta\":{\"name\":\"English\"},\"strings\":{\"fallback\":\"Default\"},\"plurals\":{\"count\":{\"one\":\"%1$d item\",\"other\":\"%1$d items\"}},\"arrays\":{\"list\":[\"One\",\"Two\"]}}");
        write("zh-CN","{\"meta\":{\"name\":\"中文\"},\"plurals\":{\"count\":{\"other\":\"%1$d 项\"}}}");
        Path output=temp.newFolder("out").toPath();new I18nGenerator(input).generate(output);
        String zh=Files.readString(output.resolve("values-b+zh+CN/strings.xml"));
        assertFalse(zh.contains("fallback")); assertFalse(zh.contains("quantity=\"one\""));
        write("zh-CN","{\"meta\":{\"name\":\"中文\"},\"arrays\":{\"list\":[\"一\"]}}"); rejects("array length");
    }
    @Test public void canonicalLocaleAndProtectedTokensAreValidated() throws Exception {
        write("en","{\"meta\":{\"name\":\"English\"},\"strings\":{\"key\":\"Use %P%\"},\"options\":{\"key\":{\"formatted\":false,\"preserve\":[\"%P%\"]}}}");
        write("zh-cn",pack("\"key\":\"%P%\"")); rejects("canonical");
        Files.delete(input.resolve("zh-cn.json"));write("zh-CN",pack("\"key\":\"Missing\"")); rejects("missing protected token");
    }
    @Test public void outputIsDeterministicAndEscapesAndroidSyntax() throws Exception {
        write("en",pack("\"key\":\"  @hello & <world> \\\"quoted\\\" apostrophe's\\nsecond line  \""));
        Path output=temp.newFolder("first").toPath(),other=temp.newFolder("second").toPath();
        new I18nGenerator(input).generate(output);new I18nGenerator(input).generate(other);
        assertArrayEquals(Files.readAllBytes(output.resolve("values/strings.xml")),Files.readAllBytes(other.resolve("values/strings.xml")));
        assertTrue(Files.readString(output.resolve("values/strings.xml")).contains("&amp; &lt;world&gt;"));
    }
}
