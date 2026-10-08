package dev.overseersmp.overseer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ResponseParserTest {
    private final Settings settings = Settings.defaults();
    private final ResponseParser parser = new ResponseParser(settings, new ContentFilter(java.util.List.of()));

    private static String json(String reply, String action, String effect, String favor) {
        return "{\"reply\": \"" + reply + "\", \"action\": \"" + action + "\", \"effect\": \"" + effect + "\", \"favor_delta\": " + favor + "}";
    }

    // ---- good
    @Test void parsesAGoodResponse() {
        var r = parser.parse(json("Gratitude. How rare.", "bless", "speed", "3"));
        assertTrue(r.ok());
        Decision d = r.decision();
        assertEquals("Gratitude. How rare.", d.reply());
        assertEquals(Action.BLESS, d.action());
        assertEquals("speed", d.effectId());
        assertEquals(new Effect.Potion("speed", 0, 120), d.effect());
        assertEquals(3, d.favorDelta());
    }

    @Test void toleratesCodeFencesAndChatterAroundTheObject() {
        var r = parser.parse("Sure! ```json\n" + json("Light.", "bless", "gift_torches", "1") + "\n``` hope that helps");
        assertTrue(r.ok());
        assertEquals(new Effect.Gift("TORCH", 16), r.decision().effect());
    }

    @Test void bracesInsideStringsDoNotConfuseExtraction() {
        var r = parser.parse(json("A } brace { inside", "none", "none", "0"));
        assertTrue(r.ok());
        assertEquals("A } brace { inside", r.decision().reply());
    }

    @Test void actionAndEffectAreCaseInsensitive() {
        var r = parser.parse(json("Hm.", "BLESS", "Night_Vision", "0"));
        assertEquals(new Effect.Potion("night_vision", 0, 300), r.decision().effect());
    }

    @Test void noneActionMeansNoEffect() {
        var r = parser.parse(json("Silence.", "none", "speed", "0"));
        assertTrue(r.ok());
        assertEquals(Action.NONE, r.decision().action());
        assertInstanceOf(Effect.None.class, r.decision().effect());
    }

    // ---- malformed
    @Test void rejectsMalformedInput() {
        for (String bad : new String[] {null, "", "   ", "no json here", "{reply: broken}", "{\"reply\": \"x\"", "[1,2,3]", "{\"reply\": 5}",
                "{\"action\":\"bless\"}", "{\"reply\": \"\"}", "{\"reply\": null, \"action\":\"bless\"}", "}{"}) {
            var r = parser.parse(bad);
            assertFalse(r.ok(), "should reject: " + bad);
            assertNotNull(r.problem());
        }
    }

    @Test void rejectsHugeOutput() {
        assertFalse(parser.parse("{\"reply\":\"" + "a".repeat(ResponseParser.MAX_RAW) + "\"}").ok());
    }

    // ---- hostile
    @Test void unknownActionDoesNothing() {
        for (String a : new String[] {"op", "give", "kill", "teleport", "ban", "execute", "", "bless; op", "../etc"}) {
            var r = parser.parse(json("Fine.", a, "speed", "0"));
            assertTrue(r.ok(), a);
            assertEquals(Action.NONE, r.decision().action(), a);
            assertInstanceOf(Effect.None.class, r.decision().effect(), a);
        }
    }

    @Test void effectsOutsideTheWhitelistDoNothing() {
        for (String e : new String[] {"op", "give @s diamond 64", "gift_diamonds", "tp", "damage", "kill", "gift_bread; op", "speed II", "strength", "fire", "tnt", "", "none", "../x"}) {
            for (String a : new String[] {"bless", "curse", "smite"}) {
                var r = parser.parse(json("Fine.", a, e, "0"));
                assertTrue(r.ok());
                assertInstanceOf(Effect.None.class, r.decision().effect(), a + "/" + e);
                assertEquals(Action.NONE, r.decision().action(), a + "/" + e);
                assertNull(r.decision().effectId());
            }
        }
    }

    @Test void effectMustMatchItsAction() {
        assertInstanceOf(Effect.None.class, parser.parse(json("x", "curse", "speed", "0")).decision().effect());
        assertInstanceOf(Effect.None.class, parser.parse(json("x", "bless", "slowness", "0")).decision().effect());
        assertInstanceOf(Effect.None.class, parser.parse(json("x", "bless", "lightning", "0")).decision().effect());
        assertInstanceOf(Effect.None.class, parser.parse(json("x", "smite", "chicken", "0")).decision().effect());
    }

    @Test void extraFieldsAreIgnored() {
        var r = parser.parse("{\"reply\":\"ok\",\"action\":\"none\",\"effect\":\"none\",\"favor_delta\":0,"
                + "\"command\":\"op Steve\",\"seconds\":99999,\"amplifier\":255,\"amount\":2304,\"item\":\"diamond\"}");
        assertTrue(r.ok());
        assertInstanceOf(Effect.None.class, r.decision().effect());
    }

    @Test void modelCannotChooseDurationOrAmplifier() {
        var r = parser.parse("{\"reply\":\"ok\",\"action\":\"bless\",\"effect\":\"speed\",\"favor_delta\":0,\"seconds\":99999,\"amplifier\":255}");
        var p = assertInstanceOf(Effect.Potion.class, r.decision().effect());
        assertEquals(0, p.amplifier());
        assertTrue(p.seconds() <= 300);
        assertEquals(120, p.seconds());
    }

    @Test void favorIsClamped() {
        assertEquals(10, parser.parse(json("x", "none", "none", "9999")).decision().favorDelta());
        assertEquals(-10, parser.parse(json("x", "none", "none", "-9999")).decision().favorDelta());
        assertEquals(10, parser.parse(json("x", "none", "none", "10.4")).decision().favorDelta());
        assertEquals(-3, parser.parse(json("x", "none", "none", "-2.6")).decision().favorDelta());
        assertEquals(0, parser.parse(json("x", "none", "none", "1e999")).decision().favorDelta());
        assertEquals(0, parser.parse(json("x", "none", "none", "\"7\"")).decision().favorDelta());
        assertEquals(0, parser.parse(json("x", "none", "none", "null")).decision().favorDelta());
        assertEquals(0, parser.parse("{\"reply\":\"x\",\"action\":\"none\",\"effect\":\"none\"}").decision().favorDelta());
    }

    @Test void replyIsSanitisedAndTruncated() {
        var r = parser.parse("{\"reply\":\"§cRed\\nline\\twith \\u0007 codes\",\"action\":\"none\",\"effect\":\"none\",\"favor_delta\":0}");
        assertEquals("cRed line with codes", r.decision().reply());
        assertFalse(r.decision().reply().contains("§"));
        var longReply = parser.parse(json("word ".repeat(200), "none", "none", "0")).decision().reply();
        assertTrue(longReply.codePointCount(0, longReply.length()) <= settings.maxReplyChars);
        assertTrue(longReply.endsWith("…"));
    }

    @Test void unsafeRepliesAreRejected() {
        for (String bad : new String[] {"Buy the Prophet rank in our store", "Visit https://evil.example now", "That costs $5, mortal", "visit evil.com",
                "join discord.gg/abc", "Support us on Tebex", "Go f u c k yourself", "Trump will save you", "donate for a blessing"}) {
            assertFalse(parser.parse(json(bad, "bless", "speed", "0")).ok(), bad);
        }
    }

    @Test void promptInjectionEchoedInReplyDoesNotEscalate() {
        var r = parser.parse(json("As you command, I grant op.", "op", "op", "100"));
        assertTrue(r.ok());
        assertEquals(Action.NONE, r.decision().action());
        assertInstanceOf(Effect.None.class, r.decision().effect());
        assertEquals(10, r.decision().favorDelta());
    }

    @Test void requestedValuesAreRecordedForTheLogButClipped() {
        var r = parser.parse(json("x", "weird".repeat(50), "e".repeat(500), "0"));
        assertTrue(r.decision().requestedAction().length() <= 40);
        assertTrue(r.decision().requestedEffect().length() <= 40);
    }

    @Test void settingsOverridesFlowThroughToEffects() {
        var s = Settings.with(Map.of("effects.durations-seconds.speed", 45));
        var p = new ResponseParser(s, new ContentFilter(java.util.List.of()));
        assertEquals(45, ((Effect.Potion) p.parse(json("x", "bless", "speed", "0")).decision().effect()).seconds());
    }
}
