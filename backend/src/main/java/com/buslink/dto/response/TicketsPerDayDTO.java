package com.buslink.dto.response;

import java.time.LocalDate;

public record TicketsPerDayDTO(LocalDate date, Long ticketCount) {}
