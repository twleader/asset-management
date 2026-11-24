package com.steven.assets.srpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;

/** Complete static L0 identity; transport clocks and live valuation are outside the supplied snapshot. */
public final class SrppAssetSnapshotIdentity {
    private SrppAssetSnapshotIdentity() {}

    /** The capture wire format represents decimal values as canonical strings, retaining integer identities. */
    public static JsonNode normalize(JsonNode node) {
        if (node == null || node.isNull()) return NullNode.instance;
        if (node.isFloatingPointNumber() || node.isBigDecimal())
            return TextNode.valueOf(node.decimalValue().stripTrailingZeros().toPlainString());
        if (node.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            node.fields().forEachRemaining(e -> result.set(e.getKey(), normalize(e.getValue())));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            node.forEach(value -> result.add(normalize(value)));
            return result;
        }
        return node.deepCopy();
    }

    public static boolean matches(JsonNode captured, JsonNode current) {
        return captured != null && captured.isObject() && current != null && current.isObject()
                && SrppJcs.canonicalize(normalize(captured)).equals(SrppJcs.canonicalize(normalize(current)));
    }

    public static String revision(long snapshotId, JsonNode snapshot) {
        if (snapshot == null || !snapshot.isObject() || !snapshot.path("id").isIntegralNumber()
                || snapshotId <= 0 || snapshot.path("id").asLong() != snapshotId)
            throw new IllegalArgumentException("Invalid static asset snapshot identity");
        return "snapshot-" + snapshotId + ":" + SrppJcs.hash(normalize(snapshot));
    }
}
