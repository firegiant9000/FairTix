package com.fairtix.venues.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Payload for creating a new venue")
public record CreateVenueRequest(
        @Schema(description = "Venue name", example = "Midtown Demo Garden")
        @NotBlank String name,
        @Schema(description = "Street address", example = "400 Demo Street")
        String address,
        @Schema(description = "City", example = "New York")
        String city,
        @Schema(description = "Country", example = "USA")
        String country,
        @Schema(description = "Maximum seating capacity")
        Integer capacity,
        @Schema(description = "Latitude (WGS84), optional", example = "40.750504")
        @DecimalMin(value = "-90.0") @DecimalMax(value = "90.0")
        Double latitude,
        @Schema(description = "Longitude (WGS84), optional", example = "-73.993439")
        @DecimalMin(value = "-180.0") @DecimalMax(value = "180.0")
        Double longitude) {
}
