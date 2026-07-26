package com.buslink.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
        name = "route_stop",
        uniqueConstraints = {
            @UniqueConstraint(columnNames = {"route_id", "stop_sequence"}),
            @UniqueConstraint(columnNames = {"route_id", "stop_name"})
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
public class RouteStop extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID routeStopId;

    @Column(nullable = false)
    private UUID routeId;

    @Column(nullable = false)
    private String stopName;

    @Column(nullable = false)
    private Integer stopSequence;

    @Column(nullable = false)
    private Integer stageNumber;
}
