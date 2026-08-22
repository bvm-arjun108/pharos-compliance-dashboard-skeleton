package com.pharos.compliance.batch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Filter values supplied by the backend")
public record BatchFilterOptionsResponse(List<CountryOptionResponse> countries) {}
