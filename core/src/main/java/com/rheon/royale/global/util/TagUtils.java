package com.rheon.royale.global.util;

import com.rheon.royale.global.error.BusinessException;
import com.rheon.royale.global.error.ErrorCode;

public final class TagUtils {

    private static final String TAG_PATTERN = "^#?[0-9A-Z]{3,12}$";

    private TagUtils() {}

    /**
     * 클래시로얄 태그를 API 요청에 쓸 수 있도록 URL 인코딩한다.
     * "#ABC123" → "%23ABC123"
     */
    public static String encode(String tag) {
        validate(tag);
        return normalize(tag).replace("#", "%23");
    }

    /**
     * 태그를 표준 형식(# 포함, 대문자)으로 정규화한다.
     */
    public static String normalize(String tag) {
        if (tag == null || tag.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PLAYER_TAG);
        }
        String upper = tag.trim().toUpperCase();
        return upper.startsWith("#") ? upper : "#" + upper;
    }

    private static void validate(String tag) {
        if (tag == null || !tag.trim().toUpperCase().matches(TAG_PATTERN)) {
            throw new BusinessException(ErrorCode.INVALID_PLAYER_TAG);
        }
    }
}
