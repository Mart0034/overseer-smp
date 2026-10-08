package dev.overseersmp.overseer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** A broken plugin.yml stops the whole plugin from loading (it happened once: an unquoted colon). CI must catch that. */
class PluginYmlTest {
    @SuppressWarnings("unchecked")
    private Map<String, Object> load(String name) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, name);
            return new Yaml().load(in);
        }
    }

    @Test @SuppressWarnings("unchecked") void pluginYmlParsesAndDeclaresEveryCommand() throws Exception {
        Map<String, Object> y = load("plugin.yml");
        assertEquals("Overseer", y.get("name"));
        assertEquals("dev.overseersmp.overseer.OverseerPlugin", y.get("main"));
        Class.forName((String) y.get("main"));
        Map<String, Map<String, Object>> cmds = (Map<String, Map<String, Object>>) y.get("commands");
        for (String c : new String[] {"pray", "overseer", "favor", "fame"}) {
            assertTrue(cmds.containsKey(c), c);
            assertNotNull(cmds.get(c).get("description"), c);
            assertNotNull(cmds.get(c).get("permission"), c);
        }
        Map<String, Object> perms = (Map<String, Object>) y.get("permissions");
        for (var e : cmds.entrySet()) assertTrue(perms.containsKey(e.getValue().get("permission")), "permission declared for " + e.getKey());
    }

    @Test void configYmlAndOtherYamlResourcesParse() throws Exception {
        Map<String, Object> c = load("config.yml");
        assertNotNull(c.get("anthropic"));
        assertNotNull(c.get("prayers"));
        assertNotNull(c.get("decree"));
        assertEquals("", ((Map<?, ?>) c.get("discord")).get("public-webhook"), "no secret may ship in the default config");
        assertEquals("", ((Map<?, ?>) c.get("anthropic")).get("api-key"));
    }
}
