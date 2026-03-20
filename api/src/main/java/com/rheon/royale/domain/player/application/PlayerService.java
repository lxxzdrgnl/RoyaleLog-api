package com.rheon.royale.domain.player.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rheon.royale.global.error.BusinessException;
import com.rheon.royale.global.error.ErrorCode;
import com.rheon.royale.global.util.TagUtils;
import com.rheon.royale.infrastructure.external.clashroyale.ClashRoyaleClient;
import com.rheon.royale.infrastructure.external.clashroyale.dto.CrPlayerProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PlayerService {

    private final ClashRoyaleClient clashRoyaleClient;
    private final ObjectMapper objectMapper;

    @Cacheable(value = "playerBattleLog", key = "'profile:' + #tag.toUpperCase().replace('#', '')")
    public CrPlayerProfile getProfile(String tag) {
        return clashRoyaleClient.getPlayerProfile(TagUtils.normalize(tag));
    }

    /**
     * 플레이어 배틀 로그 전체 목록 (캐싱) — 컨트롤러에서 슬라이싱
     */
    @Cacheable(value = "playerBattleLog", key = "'battles:' + #tag.toUpperCase().replace('#', '')")
    public List<Map<String, Object>> getBattleLog(String tag) {
        String raw = clashRoyaleClient.getBattleLog(TagUtils.normalize(tag));
        try {
            return objectMapper.readValue(raw, new TypeReference<>() {});
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 플레이어 카드별 마스터리 조회
     */
    @Cacheable(value = "playerBattleLog", key = "'masteries:' + #tag.toUpperCase().replace('#', '')")
    public List<Map<String, Object>> getMasteries(String tag) {
        String raw = clashRoyaleClient.getMasteries(TagUtils.normalize(tag));
        try {
            var wrapper = objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
            Object items = wrapper.get("items");
            if (items instanceof List<?> list) {
                return list.stream()
                        .filter(i -> i instanceof Map)
                        .map(i -> (Map<String, Object>) i)
                        .toList();
            }
            return List.of();
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }
}
