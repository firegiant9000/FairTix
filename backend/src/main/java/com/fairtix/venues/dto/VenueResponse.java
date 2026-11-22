package com.fairtix.venues.dto;

import com.fairtix.venues.domain.Venue;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

@Schema(description = "Venue details")
public record VenueResponse(
        @Schema(description = "Venue ID")
        UUID id,
        @Schema(description = "Venue name", example = "Midtown Demo Garden")
        String name,
        @Schema(description = "Street address")
        String address,
        @Schema(description = "City")
        String city,
        @Schema(description = "Country")
        String country,
        @Schema(description = "Maximum seating capacity")
        Integer capacity,
        @Schema(description = "Latitude (WGS84)", example = "40.750504")
        Double latitude,
        @Schema(description = "Longitude (WGS84)", example = "-73.993439")
        Double longitude,
        @Schema(description = "Created at")
        Instant createdAt,
        @Schema(description = "Last updated at")
        Instant updatedAt) {

    public static VenueResponse from(Venue venue) {
        return new VenueResponse(
                venue.getId(),
                venue.getName(),
                venue.getAddress(),
                venue.getCity(),
                venue.getCountry(),
                venue.getCapacity(),
                venue.getLatitude(),
                venue.getLongitude(),
                venue.getCreatedAt(),
                venue.getUpdatedAt());
    }
}
