package com.buslink.service;

import com.buslink.dto.response.ConductorActivityDTO;
import com.buslink.dto.response.RevenueByRouteDTO;
import com.buslink.dto.response.TicketsPerDayDTO;
import com.buslink.dto.response.TopRouteDTO;
import java.util.List;

public interface AnalyticsService {

    List<RevenueByRouteDTO> getRevenueByRoute();

    List<TicketsPerDayDTO> getTicketsPerDay();

    List<TopRouteDTO> getTopRoutes();

    List<ConductorActivityDTO> getConductorActivity();
}
