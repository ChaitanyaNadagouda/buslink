package com.buslink.entity;

import com.buslink.enums.TicketStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "ticket")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
public class Ticket extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID ticketId;

    @Column(nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private UUID conductorId;

    @Column(nullable = false)
    private UUID busId;

    @Column(nullable = false)
    private UUID routeId;

    @Column(nullable = false)
    private String originStop;

    @Column(nullable = false)
    private String destinationStop;

    @Column(nullable = false)
    private Integer stagesCrossed;

    @Builder.Default
    @Column(nullable = false)
    private Integer adultCount = 0;

    @Builder.Default
    @Column(nullable = false)
    private Integer childCount = 0;

    @Builder.Default
    @Column(nullable = false)
    private Integer infantCount = 0;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal adultFare;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal childFare;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalFare;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketStatus status;

    @Column(nullable = false)
    private Instant issuedAt;

    private Instant paidAt;
}
