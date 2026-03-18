package com.rheon.royale.domain.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "battle_log_raw")
public class BattleLogRaw {

    @EmbeddedId
    private BattleLogRawId id;

    @Column(name = "player_tag", nullable = false, length = 20)
    private String playerTag;

    @Column(name = "battle_type", length = 50)
    private String battleType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_json", nullable = false, columnDefinition = "jsonb")
    private String rawJson;

    @Builder
    public BattleLogRaw(BattleLogRawId id, String playerTag,
                        String battleType, String rawJson) {
        this.id = id;
        this.playerTag = playerTag;
        this.battleType = battleType;
        this.rawJson = rawJson;
    }
}
