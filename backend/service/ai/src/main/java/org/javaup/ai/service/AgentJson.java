package org.javaup.ai.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Map;
import org.postgresql.util.PGobject;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** JSONB 与稳定摘要的技术转换，不包含 Agent 决策。 */
@Component
public class AgentJson {

    private final ObjectMapper mapper;

    public AgentJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ObjectNode object() {
        return mapper.createObjectNode();
    }

    public ArrayNode array() {
        return mapper.createArrayNode();
    }

    public JsonNode node(Object value) {
        return mapper.valueToTree(value);
    }

    public <T> T read(
            JsonNode node,
            Class<T> type
    ) {
        return mapper.treeToValue(node, type);
    }

    public ObjectNode object(PGobject value) {
        JsonNode node = mapper.readTree(value.getValue());

        if (!node.isObject()) {
            throw new IllegalStateException("Agent JSONB must be an object");
        }

        return (ObjectNode) node;
    }

    public PGobject pg(JsonNode value) {
        try {
            PGobject pg = new PGobject();
            pg.setType("jsonb");
            pg.setValue(mapper.writeValueAsString(value));

            return pg;
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot encode Agent JSONB", ex);
        }
    }

    public String hash(Object value) {
        try {
            String text = mapper.writeValueAsString(canonical(node(value)));

            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = object();

            value.properties().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sorted.set(e.getKey(), canonical(e.getValue())));

            return sorted;
        }

        if (value.isArray()) {
            ArrayNode result = array();
            value.forEach(n -> result.add(canonical(n)));

            return result;
        }

        // JSONB可把1e3、1000.0等改写；摘要应基于数值而非小数的文本写法。
        if (value.isNumber()) {
            return node(value.decimalValue().stripTrailingZeros());
        }

        return value;
    }
}
