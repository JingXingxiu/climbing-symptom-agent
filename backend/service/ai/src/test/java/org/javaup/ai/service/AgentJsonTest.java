package org.javaup.ai.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/** 验证JSONB可能改变的文本写法不会误报快照损坏；不连接数据库。 */
class AgentJsonTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final AgentJson json = new AgentJson(mapper);

    @Test
    void recursivelyIgnoresObjectKeyOrderAndWhitespace() {
        var original =
                mapper.readTree(
                        """
                {"facts":{"swelling":false,"joint":"PIP"},"answers":[{"value":"两天","key":"onset"}]}
                """);
        var rewritten =
                mapper.readTree(
                        """
                { "answers": [ { "key": "onset", "value": "两天" } ],
                  "facts": { "joint": "PIP", "swelling": false } }
                """);
        assertEquals(json.hash(original), json.hash(rewritten));
        assertTrue(json.hash(original).matches("^[0-9a-f]{64}$"));
    }

    @Test
    void numericScaleAndExponentRewritesDoNotBreakStateChecksum() {
        var before =
                mapper.readTree(
                        "{\"amount\":1e3,\"nested\":{\"coordinate\":0.5000,\"zero\":-0.0}}");
        var after =
                mapper.readTree("{\"nested\":{\"zero\":0,\"coordinate\":0.5},\"amount\":1000.0}");
        assertEquals(json.hash(before), json.hash(after));
        assertEquals(json.hash(before), json.hash(json.object(json.pg(after))));
        assertNotEquals(
                json.hash(before),
                json.hash(
                        mapper.readTree(
                                "{\"amount\":1001,\"nested\":{\"coordinate\":0.5,\"zero\":0}}")));
    }

    @Test
    void preservesArrayOrderAndScalarTypes() {
        assertNotEquals(
                json.hash(mapper.readTree("{\"answers\":[1,2]}")),
                json.hash(mapper.readTree("{\"answers\":[2,1]}")));
        assertNotEquals(
                json.hash(mapper.readTree("{\"answer\":1}")),
                json.hash(mapper.readTree("{\"answer\":\"1\"}")));
        assertNotEquals(
                json.hash(mapper.readTree("{\"answer\":false}")),
                json.hash(mapper.readTree("{\"answer\":null}")));
    }
}
