package com.rheon.royale.domain.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
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

    /** 0 = 미처리, N = N버전 analyzer 처리 완료 */
    @Column(name = "analyzer_version", nullable = false)
    private int analyzerVersion = 0;

    @Column(name = "analyzer_processed_at")
    private LocalDateTime analyzerProcessedAt;

    @Builder
    public BattleLogRaw(BattleLogRawId id, String playerTag,
                        String battleType, String rawJson) {
        this.id = id;
        this.playerTag = playerTag;
        this.battleType = battleType;
        this.rawJson = rawJson;
    }
}
