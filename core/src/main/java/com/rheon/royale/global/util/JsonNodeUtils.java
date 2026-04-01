package com.rheon.royale.global.util;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * JsonNode null-safe 추출 유틸리티.
 * isMissingNode() / isNull() 체크 보일러플레이트 제거.
 */
public final class JsonNodeUtils {

    private JsonNodeUtils() {}

    /** path 값이 missing/null이면 null, 아니면 int 반환 */
    public static Integer getIntOrNull(JsonNode node, String path) {
        JsonNode value = node.path(path);
        return (!value.isMissingNode() && !value.isNull()) ? value.asInt() : null;
    }

    /** path 값이 missing/null이면 null, 아니면 String 반환 */
    public static String getStringOrNull(JsonNode node, String path) {
        JsonNode value = node.path(path);
        return (!value.isMissingNode() && !value.isNull()) ? value.asText() : null;
    }
}
