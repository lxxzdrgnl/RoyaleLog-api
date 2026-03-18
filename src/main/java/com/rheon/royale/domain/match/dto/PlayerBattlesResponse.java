package com.rheon.royale.domain.match.dto;

import java.util.List;

public record PlayerBattlesResponse(
        String playerTag,
        List<BattleLogEntry> battles
) {}
