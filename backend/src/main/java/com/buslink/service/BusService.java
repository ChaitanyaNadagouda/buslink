package com.buslink.service;

import com.buslink.dto.request.CreateBusRequestDTO;
import com.buslink.dto.response.BusResponseDTO;
import java.util.List;
import java.util.UUID;

public interface BusService {

    BusResponseDTO createBus(CreateBusRequestDTO request);

    List<BusResponseDTO> getAllBuses();

    BusResponseDTO getBusByBusId(UUID busId);
}
