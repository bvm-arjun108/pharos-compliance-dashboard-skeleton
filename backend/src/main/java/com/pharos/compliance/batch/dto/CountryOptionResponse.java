package com.pharos.compliance.batch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Country available to the batch dashboard filters")
public record CountryOptionResponse(@Schema(example = "PT") String code, @Schema(example = "Portugal") String name) {}
