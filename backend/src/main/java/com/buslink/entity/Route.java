package com.buslink.entity;

import com.buslink.enums.RouteStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "route")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
public class Route extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID routeId;

    @Column(nullable = false, unique = true)
    private String routeNumber;

    @Column(nullable = false, unique = true)
    private String routeName;

    @Column(nullable = false, precision = 8, scale = 2)
    private BigDecimal farePerStage;

    @Column(nullable = false)
    private Integer totalStops;

    @Column(nullable = false)
    private String originStop;

    @Column(nullable = false)
    private String destinationStop;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RouteStatus status;
}
