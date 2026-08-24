import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, ParamMap, Router } from '@angular/router';

type BatchStatus = 'ALL' | 'SUCCESSFUL' | 'ATTENTION' | 'NOT_YET_REPORTED';
type BatchIssueType =
  | 'ALL'
  | 'TRANSFORMATION'
  | 'MISSING_ATTEMPTS'
  | 'FILTRATION'
  | 'RECONCILIATION';
type BatchMetricFocus = 'DEFAULT' | 'REPORTED' | 'EXCLUDED';
type TransactionMetric =
  | 'SELECTED'
  | 'ATTEMPTS_FOUND'
  | 'MISSING'
  | 'EXPECTED_ELIGIBLE'
  | 'ACTUAL_ELIGIBLE'
  | 'TRANSFORMED'
  | 'FAILED'
  | 'EXPECTED_REPORTABLE'
  | 'ACTUAL_REPORTABLE'
  | 'EXCLUDED'
  | 'SIMULATED'
  | 'ALREADY_REPORTED'
  | 'SOFT_DEDUP'
  | 'FILTRATION_VARIANCE'
  | 'RECONCILIATION_VARIANCE'
  | 'TRANSFORMER_OUTPUT';
type ConditionalEvidence = 'journey' | 'rule-hits' | 'exclusions';

interface CountryOption {
  code: string;
  name: string;
}

interface BatchFilterOptionsResponse {
  countries: CountryOption[];
}

interface BatchExplorerSummary {
  allBatches: number;
  successfulBatches: number;
  attentionBatches: number;
  notYetReportedBatches: number;
}

interface BatchQueueItem {
  reportGroupId: number;
  reportGroupName: string | null;
  batchId: string;
  sequenceNumber: number;
  countryCode: string;
  countryName: string;
  reportingPeriodFrom: string | null;
  reportingPeriodTo: string | null;
  startedAt: string | null;
  completedAt: string | null;
  status: Exclude<BatchStatus, 'ALL'>;
  transformationFailures: number;
  missingAttempts: number;
  filtrationErrors: number;
  reconciliationImbalance: number;
  transformerOutput: number;
  excludedTransactions: number;
  totalIssues: number;
  discoveredTransactions: number;
}

interface BatchExplorerResponse {
  summary: BatchExplorerSummary;
  batches: BatchQueueItem[];
  matchingBatches: number;
  page: number;
  size: number;
  fromDate: string;
  toDate: string;
  status: BatchStatus;
  issueType: BatchIssueType;
  batchId: string;
  country: string;
  reportGroupId: number | null;
  reportGroupName: string | null;
  metricFocus: BatchMetricFocus;
}

interface BatchDetailsResponse {
  reportGroupId: number;
  reportGroupName: string | null;
  batchId: string;
  sequenceNumber: number;
  countryCode: string;
  countryName: string;
  reportingPeriodFrom: string | null;
  reportingPeriodTo: string | null;
  startedAt: string | null;
  completedAt: string | null;
  durationSeconds: number;
  operationalStatus: string;
  status: Exclude<BatchStatus, 'ALL'>;
  transformationFailures: number;
  missingAttempts: number;
  filtrationErrors: number;
  reconciliationImbalance: number;
  totalIssues: number;
  selectedTransactions: number;
  transactionAttemptsFound: number;
  expectedReportableTransactions: number;
  actualReportableTransactions: number;
  expectedTransformationAttempts: number;
  actualTransformationAttempts: number;
  transformedActivities: number;
  transformationBalanced: boolean;
  transformerOutput: number;
  finalDownstreamReported: number | null;
  excludedTransactions: number;
  simulatedTransactions: number;
  alreadyReportedTransactions: number;
  softDedupTransactions: number;
  journeyAvailable: boolean;
  ruleHitsAvailable: boolean;
  exclusionsAvailable: boolean;
  discoveredTransactions: number;
  stalledTransactions: number;
}

interface ReportConfigSummary {
  reportGroupId: number;
  reportGroupName: string | null;
  reportSelectionVersionId: number;
  transformerVersionId: string;
  countryName: string;
  reportType: string | null;
  active: boolean;
  mappingServiceName: string | null;
  modifiedAt: string | null;
}

interface ReportConfigExplorerResponse {
  configurations: ReportConfigSummary[];
}

@Component({
  standalone: true,
  imports: [DatePipe, DecimalPipe, FormsModule],
  templateUrl: './batch-explorer.component.html',
  styleUrl: './batch-explorer.component.css'
})
export class BatchExplorerComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly filterOptions = signal<BatchFilterOptionsResponse>({ countries: [] });
  readonly response = signal<BatchExplorerResponse | null>(null);
  readonly selectedBatch = signal<BatchQueueItem | null>(null);
  readonly selectedDetails = signal<BatchDetailsResponse | null>(null);
  readonly queueLoading = signal(false);
  readonly detailLoading = signal(false);
  readonly queueError = signal<string | null>(null);
  readonly detailError = signal<string | null>(null);
  readonly reportConfigSummary = signal<ReportConfigSummary | null>(null);
  readonly reportConfigLoading = signal(false);

  readonly fromDate = signal('');
  readonly toDate = signal('');
  readonly batchId = signal('');
  readonly country = signal('ALL');
  readonly status = signal<BatchStatus>('ALL');
  readonly issueType = signal<BatchIssueType>('ALL');
  readonly reportGroupId = signal<number | null>(null);
  readonly metricFocus = signal<BatchMetricFocus>('DEFAULT');
  readonly deepLinkSequenceNumber = signal<number | null>(null);
  readonly page = signal(0);
  readonly size = signal(50);

  ngOnInit(): void {
    this.loadFilterOptions();
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(params => {
      this.readRouteState(params);
      this.loadBatches();
    });
  }

  setBatchId(event: Event): void {
    this.batchId.set((event.target as HTMLInputElement).value);
  }

  setCountryValue(country: string): void {
    this.country.set(country);
  }

  setIssueType(event: Event): void {
    this.issueType.set((event.target as HTMLSelectElement).value as BatchIssueType);
  }

  setMetricFocus(event: Event): void {
    this.metricFocus.set((event.target as HTMLSelectElement).value as BatchMetricFocus);
  }

  applyFilters(event: SubmitEvent): void {
    event.preventDefault();
    this.updateRoute({
      batchId: this.batchId().trim() || null,
      country: this.country(),
      issueType: this.issueType(),
      metricFocus: this.metricFocus(),
      page: 0
    });
  }

  clearFilters(): void {
    this.updateRoute({ batchId: null, country: 'ALL', page: 0 });
  }

  clearDrilldown(): void {
    this.updateRoute({
      reportGroupId: null,
      status: 'ALL',
      issueType: 'ALL',
      metricFocus: 'DEFAULT',
      page: 0
    });
  }

  selectStatus(status: BatchStatus): void {
    const issueType =
      status === 'SUCCESSFUL' || status === 'NOT_YET_REPORTED' ? 'ALL' : this.issueType();
    this.updateRoute({ status, issueType, page: 0 });
  }

  selectBatch(batch: BatchQueueItem): void {
    if (this.batchKey(this.selectedBatch()) === this.batchKey(batch)) {
      return;
    }
    this.selectedBatch.set(batch);
    this.loadBatchDetails(batch);
  }

  goToDashboard(): void {
    void this.router.navigate(['/batches'], {
      queryParams: {
        fromDate: this.fromDate(),
        toDate: this.toDate(),
        batchId: this.batchId().trim() || null,
        country: this.country()
      }
    });
  }

  openReportConfig(reportGroupId: number): void {
    void this.router.navigate(['/report-config'], {
      queryParams: { reportGroupId }
    });
  }

  openBatchControlRoom(evidence?: ConditionalEvidence): void {
    const batch = this.selectedBatch();
    if (!batch) {
      return;
    }
    void this.router.navigate(
      ['/batches/control-room', batch.reportGroupId, batch.batchId, batch.sequenceNumber],
      {
        queryParams: {
          fromDate: this.fromDate(),
          toDate: this.toDate(),
          country: this.country(),
          status: this.status(),
          evidence: evidence ?? null
        }
      }
    );
  }

  openTransactionReport(metric: TransactionMetric): void {
    const batch = this.selectedBatch();
    if (!batch) {
      return;
    }
    void this.router.navigate(['/transactions'], {
      queryParams: {
        reportGroupId: batch.reportGroupId,
        batchId: batch.batchId,
        sequenceNumber: batch.sequenceNumber,
        metric,
        fromDate: this.fromDate(),
        toDate: this.toDate(),
        country: this.country(),
        status: this.status(),
        issueType: this.issueType(),
        metricFocus: this.metricFocus()
      }
    });
  }

  explorerTitle(): string {
    if (this.metricFocus() === 'REPORTED') {
      return 'Highest Transformer Output Batches';
    }
    if (this.metricFocus() === 'EXCLUDED') {
      return 'Highest Excluded Transactions Batches';
    }
    if (this.issueType() !== 'ALL') {
      return `${this.issueLabel(this.issueType())} Batches`;
    }
    if (this.status() === 'SUCCESSFUL') {
      return 'Successful Batches';
    }
    if (this.status() === 'ATTENTION') {
      return 'Batches Needing Attention';
    }
    if (this.status() === 'NOT_YET_REPORTED') {
      return 'Not Yet Reported Batches';
    }
    return 'Batch Explorer';
  }

  reportGroupContextName(): string | null {
    const reportGroupId = this.reportGroupId();
    if (reportGroupId === null) {
      return null;
    }
    return this.response()?.reportGroupName ?? `Report Group ${reportGroupId}`;
  }

  drilldownFocusLabel(): string {
    if (this.metricFocus() === 'REPORTED') {
      return 'Batches with transformer output, highest volume first';
    }
    if (this.metricFocus() === 'EXCLUDED') {
      return 'Batches with excluded transactions, highest volume first';
    }
    if (this.issueType() !== 'ALL') {
      return `${this.issueLabel(this.issueType())} evidence`;
    }
    if (this.status() === 'SUCCESSFUL') {
      return 'Successful batches';
    }
    if (this.status() === 'ATTENTION') {
      return 'Batches needing attention';
    }
    if (this.status() === 'NOT_YET_REPORTED') {
      return 'Batches not yet reported';
    }
    return 'All batches';
  }

  issueLabel(issueType: BatchIssueType): string {
    switch (issueType) {
      case 'TRANSFORMATION':
        return 'Transformation Failure';
      case 'MISSING_ATTEMPTS':
        return 'Missing Attempt';
      case 'FILTRATION':
        return 'Filtration Error';
      case 'RECONCILIATION':
        return 'Reconciliation Imbalance';
      default:
        return 'All';
    }
  }

  countryContext(): string {
    if (this.country() === 'ALL') {
      return 'All countries';
    }
    return (
      this.filterOptions().countries.find(option => option.code === this.country())?.name ??
      this.country()
    );
  }

  reportPeriodLabel(details: BatchDetailsResponse): string {
    const from = this.dateOnly(details.reportingPeriodFrom);
    const to = this.dateOnly(details.reportingPeriodTo);
    if (!from && !to) {
      return 'Reporting period unavailable';
    }
    return from === to || !to ? `Report period ${from}` : `Report period ${from} – ${to}`;
  }

  formatDuration(seconds: number): string {
    const minutes = Math.floor(seconds / 60);
    const remainingSeconds = seconds % 60;
    return `${minutes}m ${String(remainingSeconds).padStart(2, '0')}s`;
  }

  attemptCoverage(details: BatchDetailsResponse): string {
    if (details.selectedTransactions === 0) {
      return 'N/A';
    }
    return `${((details.transactionAttemptsFound / details.selectedTransactions) * 100).toFixed(1)}%`;
  }


  barShare(numerator: number, denominator: number): number {
    return denominator <= 0 ? 0 : Math.min(100, (numerator / denominator) * 100);
  }

  barRemainder(denominator: number, ...parts: number[]): number {
    return Math.max(0, denominator - parts.reduce((sum, part) => sum + part, 0));
  }

  hasEvidence(details: BatchDetailsResponse): boolean {
    return details.journeyAvailable || details.ruleHitsAvailable || details.exclusionsAvailable;
  }

  batchKey(batch: BatchQueueItem | null): string {
    return batch
      ? `${batch.reportGroupId}:${batch.batchId}:${batch.sequenceNumber}`
      : '';
  }

  private loadFilterOptions(): void {
    this.http.get<BatchFilterOptionsResponse>('/api/v1/batches/filter-options').subscribe({
      next: options => this.filterOptions.set(options),
      error: () => this.filterOptions.set({ countries: [] })
    });
  }

  private loadBatches(): void {
    this.queueLoading.set(true);
    this.queueError.set(null);
    this.selectedBatch.set(null);
    this.selectedDetails.set(null);
    this.reportConfigSummary.set(null);

    let params = new HttpParams()
      .set('fromDate', this.fromDate())
      .set('toDate', this.toDate())
      .set('status', this.status())
      .set('issueType', this.issueType())
      .set('batchId', this.batchId().trim())
      .set('country', this.country())
      .set('metricFocus', this.metricFocus())
      .set('page', this.page())
      .set('size', this.size());
    if (this.reportGroupId() !== null) {
      params = params.set('reportGroupId', this.reportGroupId()!);
    }

    this.http.get<BatchExplorerResponse>('/api/v1/batches', { params }).subscribe({
      next: response => {
        this.response.set(response);
        this.queueLoading.set(false);
        const requestedKey =
          this.reportGroupId() !== null &&
          this.batchId().trim() &&
          this.deepLinkSequenceNumber() !== null
            ? `${this.reportGroupId()}:${this.batchId().trim()}:${this.deepLinkSequenceNumber()}`
            : null;
        const matchedBatch = requestedKey
          ? (response.batches.find(batch => this.batchKey(batch) === requestedKey) ?? null)
          : null;
        const targetBatch = matchedBatch ?? response.batches[0] ?? null;
        this.selectedBatch.set(targetBatch);
        if (targetBatch) {
          this.loadBatchDetails(targetBatch);
        }
      },
      error: () => {
        this.response.set(null);
        this.queueLoading.set(false);
        this.queueError.set('The batch work queue could not be loaded.');
      }
    });
  }

  private loadBatchDetails(batch: BatchQueueItem): void {
    this.detailLoading.set(true);
    this.detailError.set(null);
    this.selectedDetails.set(null);
    const batchId = encodeURIComponent(batch.batchId);
    const url = `/api/v1/batches/${batch.reportGroupId}/${batchId}/${batch.sequenceNumber}`;
    this.http.get<BatchDetailsResponse>(url).subscribe({
      next: details => {
        if (this.batchKey(this.selectedBatch()) === this.batchKey(batch)) {
          this.selectedDetails.set(details);
          this.detailLoading.set(false);
        }
      },
      error: () => {
        this.detailLoading.set(false);
        this.detailError.set('The selected batch preview could not be loaded.');
      }
    });
    this.loadReportConfigSummary(batch.reportGroupId);
  }

  private loadReportConfigSummary(reportGroupId: number): void {
    this.reportConfigLoading.set(true);
    this.reportConfigSummary.set(null);
    const params = new HttpParams().set('reportGroupId', reportGroupId).set('status', 'ALL');
    this.http.get<ReportConfigExplorerResponse>('/api/v1/report-configs', { params }).subscribe({
      next: response => {
        if (this.selectedBatch()?.reportGroupId === reportGroupId) {
          this.reportConfigSummary.set(response.configurations[0] ?? null);
          this.reportConfigLoading.set(false);
        }
      },
      error: () => {
        this.reportConfigLoading.set(false);
        this.reportConfigSummary.set(null);
      }
    });
  }

  private readRouteState(params: ParamMap): void {
    const defaults = this.defaultPeriod();
    this.fromDate.set(params.get('fromDate') ?? defaults.fromDate);
    this.toDate.set(params.get('toDate') ?? defaults.toDate);
    this.batchId.set(params.get('batchId') ?? '');
    this.country.set(params.get('country') ?? 'ALL');
    this.status.set(this.parseStatus(params.get('status')));
    this.issueType.set(this.parseIssueType(params.get('issueType')));
    this.reportGroupId.set(this.parseReportGroupId(params.get('reportGroupId')));
    this.metricFocus.set(this.parseMetricFocus(params.get('metricFocus')));
    this.page.set(Math.max(0, Number(params.get('page') ?? 0) || 0));
    this.deepLinkSequenceNumber.set(this.parseReportGroupId(params.get('sequenceNumber')));
  }

  private updateRoute(queryParams: Record<string, string | number | null>): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams,
      queryParamsHandling: 'merge'
    });
  }

  private parseStatus(value: string | null): BatchStatus {
    return value === 'SUCCESSFUL' || value === 'ATTENTION' || value === 'NOT_YET_REPORTED'
      ? value
      : 'ALL';
  }

  private parseIssueType(value: string | null): BatchIssueType {
    return value === 'TRANSFORMATION' ||
      value === 'MISSING_ATTEMPTS' ||
      value === 'FILTRATION' ||
      value === 'RECONCILIATION'
      ? value
      : 'ALL';
  }

  private parseReportGroupId(value: string | null): number | null {
    if (value === null || value.trim() === '') {
      return null;
    }
    const reportGroupId = Number(value);
    return Number.isInteger(reportGroupId) && reportGroupId > 0 ? reportGroupId : null;
  }

  private parseMetricFocus(value: string | null): BatchMetricFocus {
    return value === 'REPORTED' || value === 'EXCLUDED' ? value : 'DEFAULT';
  }

  private defaultPeriod(): { fromDate: string; toDate: string } {
    const toDate = new Date();
    const fromDate = new Date(toDate);
    fromDate.setDate(fromDate.getDate() - 6);
    return { fromDate: this.toLocalDate(fromDate), toDate: this.toLocalDate(toDate) };
  }

  private toLocalDate(date: Date): string {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  }

  private dateOnly(value: string | null): string {
    return value?.slice(0, 10) ?? '';
  }
}
