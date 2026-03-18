package com.rheon.royale.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@EqualsAndHashCode
public class BattleLogRawId implements Serializable {

    @Column(name = "battle_id", length = 32)
    private String battleId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
