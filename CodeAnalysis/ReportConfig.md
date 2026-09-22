### Report Config Analysis

## 1. View — Configuration catalogue

*(Route: `/report-config`, marked "READ ONLY" — this page is a browsable reference for how each
report group is configured, not a place to change anything. Shows summary tiles — Report Groups,
Active Configs, Countries, Objective Reports, Subjective Reports — then a filterable, sortable list
of every configuration version on file. Real example captured: 11 distinct report groups, 36 active
configuration rows, spanning 8 countries, out of 44 total configuration versions across all of
history.)*

### Query — Summary tiles

```sql
select
  count(distinct "filtered_configs"."rpt_grp_id") as "totalConfigurations",
  count(*) filter (where "filtered_configs"."config_active_flag" = true) as "activeConfigurations",
  count(distinct upper(trim("filtered_configs"."country_code"))) filter (where (
    "filtered_configs"."country_code" is not null
    and trim("filtered_configs"."country_code") <> ''
  )) as "representedCountries",
  count(*) filter (where lower(trim("filtered_configs"."reg_rpt_type")) = 'objective') as "objectiveConfigurations",
  count(*) filter (where lower(trim("filtered_configs"."reg_rpt_type")) = 'subjective') as "subjectiveConfigurations"
from (
  select "configs_with_active_flag".* -- every report_group_config column, plus...
  from (
    select
      "pharos"."report_group_config".*,
      coalesce("pharos"."report_group_config"."rpt_config_active_flag", false) as "config_active_flag"
    from "pharos"."report_group_config"
  ) as "configs_with_active_flag"
  where (true and true and true and true) -- report group id / country / status / report type filters, all "ALL" here
) as "filtered_configs"
```

*Captured live, no filters applied. Result: `{"totalConfigurations": 11, "activeConfigurations": 36, "representedCountries": 8, "objectiveConfigurations": 20, "subjectiveConfigurations": 24}`.*

### Plain English

`report_group_config` keeps **one row per configuration version**, not one row per report group —
every time a report group's mapping, selection rules, or transformer version changes, a new row is
added rather than the old one being overwritten, so the full change history stays on file. That's
why "11 report groups" and "44 matching configurations" (in the screenshot) are both true at once:
11 is `count(distinct rpt_grp_id)` — how many actual report groups exist — while 44 is a plain
`count(*)` across every version of every one of them. "Active Configs" counts rows flagged as
currently enabled for processing (a report group can have several inactive historical versions and
one active one). "Objective Reports" and "Subjective Reports" each count rows whose report type
matches, at the same version grain as everything else in this row (20 + 24 = 44, the full count).

> **Fixed**: the page's "Subjective reports" tile used to read **-9**. It wasn't computed by this
> query at all — the frontend derived it client-side as `totalConfigurations -
> objectiveConfigurations` (11 − 20 = **-9**), mixing two different units: `totalConfigurations`
> counts distinct *report groups* (11) while `objectiveConfigurations` counts configuration
> *versions* (20). The fix adds a `subjectiveConfigurations` count to this query, computed the same
> way as `objectiveConfigurations` (same version grain, just matching `'subjective'` instead of
> `'objective'`), and the frontend now displays that directly instead of subtracting mismatched
> units. See `ReportConfigSummaryProjection`/`ReportConfigSummaryResponse` and
> `ReportGroupConfigRepository.getSummary`.

### Code Flow — Summary tiles

- **API**: `GET /api/v1/report-configs` — `ReportConfigApi.getReportConfigs` (`com.pharos.compliance.reportgroup.api.ReportConfigApi`)
- **Controller**: `ReportConfigController` (`com.pharos.compliance.reportgroup.controller.ReportConfigController`)
- **Service**: `ReportConfigServiceImpl` (`com.pharos.compliance.reportgroup.service.impl.ReportConfigServiceImpl`)
- **Repository**: `ReportGroupConfigRepository` (`com.pharos.compliance.reportgroup.repository.ReportGroupConfigRepository`) — the "Summarize report-group configurations matching the selected filters" query, alongside "Load report-group configurations matching the selected filters" (the list below) and "Load the configured regulatory report types" (the Report Type filter dropdown's options)
- **DTO**: `ReportConfigSummaryResponse` (`com.pharos.compliance.reportgroup.dto.ReportConfigSummaryResponse`)
- **Frontend**: `report-config.component.ts`

## 2. View — Configuration list and detail panel

*(The scrollable list below the tiles, plus the detail panel that opens when a row is clicked —
shown in the screenshot as "FRANCE OBJECTIVE, ID 9001, Selection v104, Transformer vload-4.0".)*

### Query — List

```sql
select
  "filtered_configs"."rpt_grp_id" as "reportGroupId",
  "filtered_configs"."rpt_grp_name" as "reportGroupName",
  "filtered_configs"."rpt_selection_version_id" as "reportSelectionVersionId",
  "filtered_configs"."transformer_version_id" as "transformerVersionId",
  upper(trim("filtered_configs"."country_code")) as "countryCode",
  coalesce(nullif(trim("filtered_configs"."country_name"), ''), upper(trim("filtered_configs"."country_code"))) as "countryName",
  "filtered_configs"."region_name" as "regionName",
  "filtered_configs"."reg_rpt_type" as "reportType",
  coalesce("filtered_configs"."config_active_flag", false) as "active",
  coalesce("filtered_configs"."is_partial_report", false) as "partialReport",
  coalesce("filtered_configs"."db_lookup_enabled", false) as "databaseLookupEnabled",
  "filtered_configs"."mapping_service_name" as "mappingServiceName",
  "filtered_configs"."modified_timestamp" as "modifiedAt"
from (/* same configs_with_active_flag + filters as the summary tiles query */) as "filtered_configs"
order by
  "countryName" nulls last,
  "reportGroupName" nulls last,
  "filtered_configs"."rpt_grp_id",
  "filtered_configs"."rpt_selection_version_id" desc,
  "filtered_configs"."transformer_version_id" desc
```

### Query — One configuration's full detail

```sql
select
  "pharos"."report_group_config"."rpt_grp_id" as "reportGroupId",
  "pharos"."report_group_config"."rpt_grp_name" as "reportGroupName",
  "pharos"."report_group_config"."bizgrp_name" as "businessGroupName",
  upper(trim("pharos"."report_group_config"."country_code")) as "countryCode",
  coalesce(nullif(trim("pharos"."report_group_config"."country_name"), ''), upper(trim("pharos"."report_group_config"."country_code"))) as "countryName",
  "pharos"."report_group_config"."reg_rpt_type" as "reportType",
  coalesce("pharos"."report_group_config"."rpt_config_active_flag", false) as "active",
  "pharos"."report_group_config"."rpt_selection_version_id" as "reportSelectionVersionId",
  "pharos"."report_group_config"."transformer_version_id" as "transformerVersionId",
  "pharos"."report_group_config"."created_timestamp" as "createdAt",
  "pharos"."report_group_config"."modified_timestamp" as "modifiedAt",
  coalesce("pharos"."report_group_config"."db_lookup_enabled", false) as "databaseLookupEnabled",
  coalesce("pharos"."report_group_config"."is_blank_report", false) as "blankReport",
  coalesce("pharos"."report_group_config"."is_non_transactional_report", false) as "nonTransactionalReport",
  coalesce("pharos"."report_group_config"."is_partial_report", false) as "partialReport",
  "pharos"."report_group_config"."mapping_service_name" as "mappingServiceName",
  cast("pharos"."report_group_config"."transformer_config" as varchar) as "transformerConfig",
  "pharos"."report_group_config"."rpt_selection" as "reportSelection",
  "pharos"."report_group_config"."reg_reportable_activity_columns" as "reportableActivityColumns",
  "pharos"."report_group_config"."rule_hit_columns" as "ruleHitColumns",
  "pharos"."report_group_config"."exclusion_strategy" as "exclusionStrategy",
  "pharos"."report_group_config"."exclusion_reason" as "exclusionReason",
  cast("pharos"."report_group_config"."manipulation_strategy_metadata" as varchar) as "manipulationStrategyMetadata",
  cast("pharos"."report_group_config"."reconciliation_strategy_metadata" as varchar) as "reconciliationStrategyMetadata"
from "pharos"."report_group_config"
where (
  "pharos"."report_group_config"."rpt_grp_id" = 9001
  and "pharos"."report_group_config"."rpt_selection_version_id" = 104
  and "pharos"."report_group_config"."transformer_version_id" = 'load-4.0'
)
```

*Captured live for FRANCE OBJECTIVE, selection version 104, transformer `load-4.0`.*

### Plain English

The list query is a straightforward "show me every configuration row, dressed up for display" —
filling in a country name when one isn't set (falling back to the country code), defaulting
nullable flags to `false` so the UI never has to special-case a missing value, and sorting so
configurations naturally group by country, then report group, with the newest versions on top.

The detail query is "give me everything about one exact version" — identified by the composite key
(report group, selection version, transformer version), since that's how this table tracks history:
a report group doesn't have one configuration, it has a whole timeline of them, and this is how you
pull up one specific point in that timeline. The interesting columns for a compliance reviewer are
the strategy ones — `exclusionStrategy`/`exclusionReason` (how this report group decides what to
leave out), `manipulationStrategyMetadata`/`reconciliationStrategyMetadata` (JSON blobs describing
how data gets transformed and reconciled), and the three boolean flags (`blankReport`,
`nonTransactionalReport`, `partialReport`) that change how the report is generated for
edge-case report groups.

### Code Flow — List and detail

- **API**: `GET /api/v1/report-configs` (`getReportConfigs`), `GET /api/v1/report-configs/{reportGroupId}/{reportSelectionVersionId}/{transformerVersionId}` (`getReportConfigDetails`), `GET /api/v1/report-configs/report-groups` (`getReportGroupOptions`), `GET /api/v1/report-configs/filter-options` (`getReportConfigFilterOptions`) — all on `ReportConfigApi` (`com.pharos.compliance.reportgroup.api.ReportConfigApi`)
- **Controller**: `ReportConfigController` (`com.pharos.compliance.reportgroup.controller.ReportConfigController`)
- **Service**: `ReportConfigServiceImpl` (`com.pharos.compliance.reportgroup.service.impl.ReportConfigServiceImpl`)
- **Repository**: `ReportGroupConfigRepository` (`com.pharos.compliance.reportgroup.repository.ReportGroupConfigRepository`)
- **DTOs**: `ReportConfigListItemResponse`, `ReportConfigDetailsResponse`, `ReportConfigExplorerResponse`, `ReportConfigCountryOptionResponse` (`com.pharos.compliance.reportgroup.dto`)
- **Frontend**: `report-config.component.ts` — also the source of the `ReportGroupOption`/`filter-options` calls reused by Batch Explorer's and Transactions Overview's own Country/Report Group dropdowns
