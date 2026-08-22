import { DatePipe } from '@angular/common';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, ParamMap, Router } from '@angular/router';

type ReportConfigStatus = 'ALL' | 'ACTIVE' | 'INACTIVE';

interface CountryOption {
  code: string;
  name: string;
}

interface ReportConfigFilterOptions {
  countries: CountryOption[];
  reportTypes: string[];
}

interface ReportConfigSummary {
  totalConfigurations: number;
  activeConfigurations: number;
  representedCountries: number;
  objectiveConfigurations: number;
}

interface ReportConfigListItem {
  reportGroupId: number;
  reportGroupName: string | null;
  reportSelectionVersionId: number;
  transformerVersionId: string;
  countryCode: string;
  countryName: string;
  regionName: string | null;
  reportType: string | null;
  active: boolean;
  partialReport: boolean;
  databaseLookupEnabled: boolean;
  mappingServiceName: string | null;
  modifiedAt: string | null;
}

interface ReportConfigExplorerResponse {
  summary: ReportConfigSummary;
  configurations: ReportConfigListItem[];
  country: string;
  status: ReportConfigStatus;
  reportType: string;
  reportGroupId: number | null;
}

interface ReportConfigDetails {
  identity: {
    reportGroupId: number;
    reportGroupName: string | null;
    businessGroupName: string | null;
    countryCode: string;
    countryName: string;
    threeLetterCountryCode: string | null;
    regionCode: string | null;
    regionName: string | null;
    reportCurrency: string | null;
    reportType: string | null;
    active: boolean;
  };
  versioning: {
    reportSelectionVersionId: number;
    transformerVersionId: string;
    createdAt: string | null;
    modifiedAt: string | null;
  };
  processingBehavior: {
    databaseLookupEnabled: boolean;
    blankReport: boolean;
    nonTransactionalReport: boolean;
    partialReport: boolean;
    reportPeriod: number | null;
    additionalData: string | null;
  };
  mapping: {
    projectKey: string | null;
    serviceName: string | null;
    acknowledgementDocumentSubtype: string | null;
    outputFileDocumentSubtype: string | null;
    submissionDocumentSubtype: string | null;
    transformerConfig: string | null;
  };
  rules: {
    inboundRuleId: string | null;
    outboundRuleId: string | null;
    reportSelection: string | null;
    reportableActivityColumns: string | null;
    ruleHitColumns: string | null;
  };
  strategies: {
    exclusionStrategy: string | null;
    exclusionReason: string | null;
    columnToCompare: string | null;
    manipulationStrategyMetadata: string | null;
    reconciliationStrategyMetadata: string | null;
  };
}

interface ConfigurationEntry {
  label: string;
  value: string;
}

interface StrategyCardView {
  id: string;
  title: string;
  configured: boolean;
  preview: string;
  emptyMessage: string;
  entries: ConfigurationEntry[];
}

@Component({
  standalone: true,
  imports: [DatePipe, FormsModule],
  templateUrl: './report-config.component.html',
  styleUrl: './report-config.component.css'
})
export class ReportConfigComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly filterOptions = signal<ReportConfigFilterOptions>({ countries: [], reportTypes: [] });
  readonly response = signal<ReportConfigExplorerResponse | null>(null);
  readonly selectedConfig = signal<ReportConfigListItem | null>(null);
  readonly selectedDetails = signal<ReportConfigDetails | null>(null);
  readonly listLoading = signal(false);
  readonly detailLoading = signal(false);
  readonly listError = signal<string | null>(null);
  readonly detailError = signal<string | null>(null);

  readonly country = signal('ALL');
  readonly status = signal<ReportConfigStatus>('ALL');
  readonly reportType = signal('ALL');
  readonly reportGroupId = signal('');

  ngOnInit(): void {
    this.loadFilterOptions();
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(params => {
      this.readRouteState(params);
      this.loadConfigurations();
    });
  }

  setCountry(value: string): void {
    this.country.set(value);
  }

  setStatus(event: Event): void {
    this.status.set((event.target as HTMLSelectElement).value as ReportConfigStatus);
  }

  setReportType(event: Event): void {
    this.reportType.set((event.target as HTMLSelectElement).value);
  }

  setReportGroupId(event: Event): void {
    const input = event.target as HTMLInputElement;
    const digitsOnly = input.value.replace(/\D/g, '').slice(0, 10);
    input.value = digitsOnly;
    this.reportGroupId.set(digitsOnly);
  }

  applyFilters(event: SubmitEvent): void {
    event.preventDefault();
    this.updateRoute({
      country: this.country(),
      status: this.status(),
      reportType: this.reportType(),
      reportGroupId: this.reportGroupId() || null
    });
  }

  clearFilters(): void {
    this.updateRoute({ country: 'ALL', status: 'ALL', reportType: 'ALL', reportGroupId: null });
  }

  selectConfig(config: ReportConfigListItem): void {
    if (this.configKey(this.selectedConfig()) === this.configKey(config)) {
      return;
    }
    this.selectedConfig.set(config);
    this.loadDetails(config);
  }

  configKey(config: ReportConfigListItem | null): string {
    return config
      ? `${config.reportGroupId}:${config.reportSelectionVersionId}:${config.transformerVersionId}`
      : '';
  }

  configuredValue(value: string | number | null | undefined): string {
    return value === null || value === undefined || value === '' ? 'Not configured' : String(value);
  }

  booleanLabel(value: boolean): string {
    return value ? 'Enabled' : 'Disabled';
  }

  csvValues(value: string | null): string[] {
    if (!value) {
      return [];
    }
    return value
      .split(',')
      .map(item => item.trim())
      .filter(Boolean);
  }

  strategyCards(details: ReportConfigDetails): StrategyCardView[] {
    const reconciliationEntries = this.configurationEntries(
      details.strategies.reconciliationStrategyMetadata
    );
    const manipulationEntries = [
      ...this.configuredEntries([
        ['Exclusion strategy', details.strategies.exclusionStrategy],
        ['Exclusion reason', details.strategies.exclusionReason],
        ['Comparison column', details.strategies.columnToCompare]
      ]),
      ...this.configurationEntries(details.strategies.manipulationStrategyMetadata)
    ];
    const transformerEntries = this.configurationEntries(details.mapping.transformerConfig);

    return [
      this.strategyCard(
        'reconciliation',
        'Reconciliation strategy',
        reconciliationEntries,
        'No reconciliation strategy metadata is configured.'
      ),
      this.strategyCard(
        'exclusion',
        'Exclusion & manipulation',
        manipulationEntries,
        'No exclusion or manipulation strategy is configured.'
      ),
      this.strategyCard(
        'transformer',
        'Transformer overrides',
        transformerEntries,
        'Standard transformer behavior is being used.'
      )
    ];
  }

  private strategyCard(
    id: string,
    title: string,
    entries: ConfigurationEntry[],
    emptyMessage: string
  ): StrategyCardView {
    return {
      id,
      title,
      configured: entries.length > 0,
      preview: entries.length > 0 ? entries[0].value : emptyMessage,
      emptyMessage,
      entries
    };
  }

  private configurationEntries(value: string | null): ConfigurationEntry[] {
    if (!value) {
      return [];
    }

    try {
      const parsed: unknown = JSON.parse(value);
      if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
        return [{ label: 'Configuration', value: this.formatConfigValue(parsed) }];
      }
      return Object.entries(parsed).map(([key, entryValue]) => ({
        label: this.formatConfigLabel(key),
        value: this.formatConfigValue(entryValue)
      }));
    } catch {
      return [{ label: 'Configuration', value }];
    }
  }

  private configuredEntries(entries: Array<[string, string | null]>): ConfigurationEntry[] {
    return entries
      .filter((entry): entry is [string, string] => Boolean(entry[1]))
      .map(([label, value]) => ({ label, value: this.formatConfigValue(value) }));
  }

  private formatConfigLabel(value: string): string {
    const words = value.replace(/([a-z])([A-Z])/g, '$1 $2').replace(/_/g, ' ').toLowerCase();
    return words.charAt(0).toUpperCase() + words.slice(1);
  }

  private formatConfigValue(value: unknown): string {
    if (Array.isArray(value)) {
      return value.map(item => this.formatConfigValue(item)).join(', ');
    }
    if (typeof value === 'object' && value !== null) {
      return JSON.stringify(value);
    }
    if (typeof value === 'string' && /^[A-Z0-9_]+$/.test(value)) {
      return value
        .toLowerCase()
        .replace(/_/g, ' ')
        .replace(/\b\w/g, character => character.toUpperCase());
    }
    return String(value);
  }

  private loadFilterOptions(): void {
    this.http
      .get<ReportConfigFilterOptions>('/api/v1/report-configs/filter-options')
      .subscribe({
        next: options => this.filterOptions.set(options),
        error: () => this.filterOptions.set({ countries: [], reportTypes: [] })
      });
  }

  private loadConfigurations(): void {
    this.listLoading.set(true);
    this.listError.set(null);
    this.selectedConfig.set(null);
    this.selectedDetails.set(null);

    let params = new HttpParams()
      .set('country', this.country())
      .set('status', this.status())
      .set('reportType', this.reportType());
    if (this.reportGroupId()) {
      params = params.set('reportGroupId', this.reportGroupId());
    }

    this.http.get<ReportConfigExplorerResponse>('/api/v1/report-configs', { params }).subscribe({
      next: response => {
        this.response.set(response);
        this.listLoading.set(false);
        const firstConfig = response.configurations[0] ?? null;
        this.selectedConfig.set(firstConfig);
        if (firstConfig) {
          this.loadDetails(firstConfig);
        }
      },
      error: () => {
        this.response.set(null);
        this.listLoading.set(false);
        this.listError.set('Report-group configurations could not be loaded.');
      }
    });
  }

  private loadDetails(config: ReportConfigListItem): void {
    this.detailLoading.set(true);
    this.detailError.set(null);
    this.selectedDetails.set(null);
    const transformerVersion = encodeURIComponent(config.transformerVersionId);
    const url = `/api/v1/report-configs/${config.reportGroupId}/${config.reportSelectionVersionId}/${transformerVersion}`;
    this.http.get<ReportConfigDetails>(url).subscribe({
      next: details => {
        if (this.configKey(this.selectedConfig()) === this.configKey(config)) {
          this.selectedDetails.set(details);
          this.detailLoading.set(false);
        }
      },
      error: () => {
        this.detailLoading.set(false);
        this.detailError.set('The selected configuration could not be loaded.');
      }
    });
  }

  private readRouteState(params: ParamMap): void {
    this.country.set(params.get('country') ?? 'ALL');
    this.status.set(this.parseStatus(params.get('status')));
    this.reportType.set(params.get('reportType') ?? 'ALL');
    this.reportGroupId.set((params.get('reportGroupId') ?? '').replace(/\D/g, '').slice(0, 10));
  }

  private parseStatus(value: string | null): ReportConfigStatus {
    return value === 'ACTIVE' || value === 'INACTIVE' ? value : 'ALL';
  }

  private updateRoute(queryParams: Record<string, string | null>): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams,
      queryParamsHandling: 'merge'
    });
  }
}
