import { DatePipe, DecimalPipe, Location } from '@angular/common';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, ParamMap, Router, RouterLink } from '@angular/router';

type TransactionMetric =
  | 'ALL'
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
type TransactionEvidenceSource = 'ALL' | 'JOURNEY' | 'EXCLUSION_AUDIT' | 'RULE_HIT';
type TransactionStage =
  | 'ALL'
  | 'SELECTION'
  | 'TRANSACTION_JOIN'
  | 'TRANSFORMATION'
  | 'EXCLUSION'
  | 'RULE_HIT';
type TransactionOutcome = 'ALL' | 'SUCCESS' | 'ERROR' | 'PENDING' | 'EXCLUDED';
type TransactionEvidenceLevel =
  | 'RECORD_LEVEL'
  | 'PARTIAL_RECORD_LEVEL'
  | 'AGGREGATE_ONLY'
  | 'NO_RECORDS';

interface TransactionReportContext {
  reportGroupId: number;
  reportGroupName: string | null;
  batchId: string;
  sequenceNumber: number;
  countryCode: string;
  countryName: string;
  reportingPeriodFrom: string | null;
  reportingPeriodTo: string | null;
}

interface TransactionEvidenceRecord {
  recordKey: string;
  identifier: string;
  mtcn: string | null;
  source: Exclude<TransactionEvidenceSource, 'ALL'>;
  stage: string | null;
  status: string | null;
  outcome: Exclude<TransactionOutcome, 'ALL'>;
  comments: string | null;
  skipReason: string | null;
  ruleId: string | null;
  exclusionReason: string | null;
  exclusionStrategy: string | null;
  reportedBatchId: string | null;
  reportingTimestamp: string | null;
  modifiedAt: string | null;
  processingComplete: boolean | null;
  currencyAmount: number | null;
  currencyCode: string | null;
  transactionDate: string | null;
  transactionSide: string | null;
  txnSource: string | null;
  activityType: string | null;
  sendDate: string | null;
  galacticId: string | null;
  bucketId: number | null;
  attemptId: number | null;
  senderName: string | null;
  receiverName: string | null;
  senderCity: string | null;
  senderCountry: string | null;
  senderPhone: string | null;
  senderDateOfBirth: string | null;
  senderIdType: string | null;
  senderIdNumber: string | null;
  receiverCity: string | null;
  receiverCountry: string | null;
  receiverPhone: string | null;
  receiverDateOfBirth: string | null;
  receiverIdType: string | null;
  receiverIdNumber: string | null;
  transactionStatus: string | null;
  transactionSubStatus: string | null;
  ruleHitsJson: string | null;
}

interface RuleHitSummary {
  ruleId: string | null;
  isReported: boolean | null;
  reportingTimestamp: string | null;
  bucketId: number | null;
  attemptId: number | null;
}

interface TransactionOutcomeBreakdown {
  successCount: number;
  errorCount: number;
  pendingCount: number;
  excludedCount: number;
  totalCount: number;
}

interface TransactionStageBreakdown {
  stage: string;
  successCount: number;
  errorCount: number;
  pendingCount: number;
  excludedCount: number;
  totalCount: number;
}

const PIPELINE_STAGE_ORDER = [
  'SELECTION',
  'TRANSACTION_JOIN',
  'TRANSFORMATION',
  'EXCLUSION',
  'RULE_HIT'
];

interface TransactionReportResponse {
  context: TransactionReportContext;
  metric: TransactionMetric;
  metricLabel: string;
  aggregateCount: number;
  availableRecordCount: number;
  matchingRecordCount: number;
  evidenceLevel: TransactionEvidenceLevel;
  evidenceMessage: string;
  outcomeBreakdown: TransactionOutcomeBreakdown;
  stageBreakdown: TransactionStageBreakdown[];
  transactions: TransactionEvidenceRecord[];
  search: string;
  source: TransactionEvidenceSource;
  stage: TransactionStage;
  outcome: TransactionOutcome;
  page: number;
  size: number;
}

@Component({
  standalone: true,
  imports: [DatePipe, DecimalPipe, RouterLink],
  templateUrl: './transaction-report.component.html',
  styleUrl: './transaction-report.component.css'
})
export class TransactionReportComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly destroyRef = inject(DestroyRef);

  readonly report = signal<TransactionReportResponse | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly hasBatchContext = signal(false);

  readonly reportGroupId = signal<number | null>(null);
  readonly batchId = signal('');
  readonly sequenceNumber = signal<number | null>(null);
  readonly metric = signal<TransactionMetric>('ALL');
  readonly search = signal('');
  readonly source = signal<TransactionEvidenceSource>('ALL');
  readonly stage = signal<TransactionStage>('ALL');
  readonly outcome = signal<TransactionOutcome>('ALL');
  readonly page = signal(0);
  readonly size = signal(25);
  readonly expandedRecordKey = signal<string | null>(null);

  ngOnInit(): void {
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(params => {
      this.readRouteState(params);
      if (this.hasBatchContext()) {
        this.loadReport();
      } else {
        this.report.set(null);
        this.loading.set(false);
      }
    });
  }

  setSearch(event: Event): void {
    this.search.set((event.target as HTMLInputElement).value);
  }

  setStage(event: Event): void {
    this.stage.set((event.target as HTMLSelectElement).value as TransactionStage);
  }

  setOutcome(event: Event): void {
    this.outcome.set((event.target as HTMLSelectElement).value as TransactionOutcome);
  }

  applyFilters(event: SubmitEvent): void {
    event.preventDefault();
    this.updateRoute({
      search: this.search().trim() || null,
      stage: this.stage(),
      outcome: this.outcome(),
      page: 0
    });
  }

  clearFilters(): void {
    this.updateRoute({ search: null, stage: 'ALL', outcome: 'ALL', page: 0 });
  }

  previousPage(): void {
    if (this.page() > 0) {
      this.updateRoute({ page: this.page() - 1 });
    }
  }

  nextPage(report: TransactionReportResponse): void {
    if ((this.page() + 1) * this.size() < report.matchingRecordCount) {
      this.updateRoute({ page: this.page() + 1 });
    }
  }

  goToPage(pageNumber: number): void {
    this.updateRoute({ page: pageNumber - 1 });
  }

  totalPages(report: TransactionReportResponse): number {
    return Math.max(1, Math.ceil(report.matchingRecordCount / this.size()));
  }

  pageNumbers(report: TransactionReportResponse): (number | '…')[] {
    const total = this.totalPages(report);
    const current = this.page() + 1;
    const delta = 2;
    const rangeStart = Math.max(2, current - delta);
    const rangeEnd = Math.min(total - 1, current + delta);

    const pages: (number | '…')[] = [1];
    if (rangeStart > 2) {
      pages.push('…');
    }
    for (let i = rangeStart; i <= rangeEnd; i++) {
      pages.push(i);
    }
    if (rangeEnd < total - 1) {
      pages.push('…');
    }
    if (total > 1) {
      pages.push(total);
    }
    return pages;
  }

  pageRange(report: TransactionReportResponse): string {
    if (report.matchingRecordCount === 0) {
      return '0 records';
    }
    const first = this.page() * this.size() + 1;
    const last = Math.min(first + report.transactions.length - 1, report.matchingRecordCount);
    return `${first}–${last} of ${report.matchingRecordCount}`;
  }

  goBack(): void {
    if (globalThis.history.length > 1) {
      this.location.back();
      return;
    }
    void this.router.navigate(['/batches/explorer']);
  }

  openBatchView(): void {
    // The batch explorer's fromDate/toDate filter is matched against the reconciliation row's
    // created_timestamp (when the batch was processed), not its reporting period — the two can be
    // weeks apart. A deliberately wide range keeps this deep link working regardless of either
    // date, since batchId + reportGroupId + sequenceNumber already pin down the exact batch.
    void this.router.navigate(['/batches/explorer'], {
      queryParams: {
        reportGroupId: this.reportGroupId(),
        batchId: this.batchId(),
        sequenceNumber: this.sequenceNumber(),
        fromDate: '2000-01-01',
        toDate: '2099-12-31'
      }
    });
  }

  outcomeShare(report: TransactionReportResponse, count: number): number {
    return report.outcomeBreakdown.totalCount === 0
      ? 0
      : (count / report.outcomeBreakdown.totalCount) * 100;
  }

  outcomeSummary(report: TransactionReportResponse): string {
    const breakdown = report.outcomeBreakdown;
    return `${breakdown.successCount} success, ${breakdown.errorCount} error, ${breakdown.pendingCount} pending, ${breakdown.excludedCount} excluded, out of ${breakdown.totalCount} total`;
  }

  setOutcomeFilter(value: TransactionOutcome): void {
    this.updateRoute({ outcome: value, page: 0 });
  }

  toggleOutcomeFilter(value: Exclude<TransactionOutcome, 'ALL'>): void {
    this.setOutcomeFilter(this.outcome() === value ? 'ALL' : value);
  }

  sortedStageBreakdown(report: TransactionReportResponse): TransactionStageBreakdown[] {
    return [...report.stageBreakdown].sort((a, b) => {
      const orderA = PIPELINE_STAGE_ORDER.indexOf(a.stage);
      const orderB = PIPELINE_STAGE_ORDER.indexOf(b.stage);
      return (orderA === -1 ? PIPELINE_STAGE_ORDER.length : orderA)
        - (orderB === -1 ? PIPELINE_STAGE_ORDER.length : orderB);
    });
  }

  stageShare(row: TransactionStageBreakdown, count: number): number {
    return row.totalCount === 0 ? 0 : (count / row.totalCount) * 100;
  }

  stageSummary(row: TransactionStageBreakdown): string {
    return `${this.stageLabel(row.stage)}: ${row.successCount} success, ${row.errorCount} error, ${row.pendingCount} pending, ${row.excludedCount} excluded, out of ${row.totalCount} total`;
  }

  toggleStageFilter(value: string): void {
    this.updateRoute({ stage: this.stage() === value ? 'ALL' : value, page: 0 });
  }

  filterStageOutcome(stageValue: string, outcomeValue: Exclude<TransactionOutcome, 'ALL'>): void {
    const alreadyActive = this.stage() === stageValue && this.outcome() === outcomeValue;
    this.updateRoute({
      stage: alreadyActive ? 'ALL' : stageValue,
      outcome: alreadyActive ? 'ALL' : outcomeValue,
      page: 0
    });
  }

  evidenceLevelLabel(level: TransactionEvidenceLevel): string {
    switch (level) {
      case 'RECORD_LEVEL':
        return 'Full record evidence';
      case 'PARTIAL_RECORD_LEVEL':
        return 'Partial record evidence';
      case 'AGGREGATE_ONLY':
        return 'Aggregate only';
      default:
        return 'No records expected';
    }
  }

  sourceLabel(source: Exclude<TransactionEvidenceSource, 'ALL'>): string {
    switch (source) {
      case 'JOURNEY':
        return 'Latest journey';
      case 'EXCLUSION_AUDIT':
        return 'Exclusion audit';
      case 'RULE_HIT':
        return 'Rule hit';
    }
  }

  stageLabel(stage: string | null): string {
    switch (stage) {
      case 'SELECTION':
        return 'Selection';
      case 'TRANSACTION_JOIN':
        return 'Transaction join';
      case 'TRANSFORMATION':
        return 'Transformation';
      case 'EXCLUSION':
        return 'Exclusion';
      case 'RULE_HIT':
        return 'Rule hit';
      default:
        return 'Not available';
    }
  }

  recordDetail(record: TransactionEvidenceRecord): string {
    if (record.source === 'RULE_HIT') {
      return record.status === 'REPORTED' ? 'Reported' : 'Not yet reported';
    }
    return (
      record.exclusionReason ??
      record.skipReason ??
      record.comments ??
      (record.processingComplete === false ? 'Processing incomplete' : 'No additional detail')
    );
  }

  toggleExpanded(recordKey: string): void {
    this.expandedRecordKey.set(this.expandedRecordKey() === recordKey ? null : recordKey);
  }

  ruleHits(record: TransactionEvidenceRecord): RuleHitSummary[] {
    if (!record.ruleHitsJson) {
      return [];
    }
    try {
      const parsed = JSON.parse(record.ruleHitsJson);
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }

  formatCurrency(record: TransactionEvidenceRecord): string {
    if (record.currencyAmount === null) {
      return 'Not available';
    }
    return record.currencyCode
      ? `${record.currencyCode} ${record.currencyAmount.toLocaleString()}`
      : record.currencyAmount.toLocaleString();
  }

  private loadReport(): void {
    this.loading.set(true);
    this.error.set(null);
    this.report.set(null);

    const params = new HttpParams()
      .set('reportGroupId', this.reportGroupId()!)
      .set('batchId', this.batchId())
      .set('sequenceNumber', this.sequenceNumber()!)
      .set('metric', this.metric())
      .set('search', this.search().trim())
      .set('source', this.source())
      .set('stage', this.stage())
      .set('outcome', this.outcome())
      .set('page', this.page())
      .set('size', this.size());

    this.http.get<TransactionReportResponse>('/api/v1/transactions/report', { params }).subscribe({
      next: report => {
        this.report.set(report);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.error.set('The transaction evidence report could not be loaded.');
      }
    });
  }

  private readRouteState(params: ParamMap): void {
    const reportGroupId = Number(params.get('reportGroupId'));
    const sequenceNumber = Number(params.get('sequenceNumber'));
    const batchId = params.get('batchId')?.trim() ?? '';

    this.reportGroupId.set(Number.isInteger(reportGroupId) && reportGroupId > 0 ? reportGroupId : null);
    this.sequenceNumber.set(Number.isInteger(sequenceNumber) && sequenceNumber > 0 ? sequenceNumber : null);
    this.batchId.set(batchId);
    this.metric.set(this.parseMetric(params.get('metric')));
    this.search.set(params.get('search') ?? '');
    this.source.set(this.parseSource(params.get('source')));
    this.stage.set(this.parseStage(params.get('stage')));
    this.outcome.set(this.parseOutcome(params.get('outcome')));
    this.page.set(Math.max(0, Number(params.get('page') ?? 0) || 0));
    this.hasBatchContext.set(this.reportGroupId() !== null && this.sequenceNumber() !== null && !!batchId);
  }

  private updateRoute(queryParams: Record<string, string | number | null>): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams,
      queryParamsHandling: 'merge'
    });
  }

  private parseMetric(value: string | null): TransactionMetric {
    const metrics: TransactionMetric[] = [
      'SELECTED', 'ATTEMPTS_FOUND', 'MISSING', 'EXPECTED_ELIGIBLE', 'ACTUAL_ELIGIBLE',
      'TRANSFORMED', 'FAILED', 'EXPECTED_REPORTABLE', 'ACTUAL_REPORTABLE', 'EXCLUDED',
      'SIMULATED', 'ALREADY_REPORTED', 'SOFT_DEDUP',
      'FILTRATION_VARIANCE', 'RECONCILIATION_VARIANCE', 'TRANSFORMER_OUTPUT'
    ];
    return metrics.includes(value as TransactionMetric) ? (value as TransactionMetric) : 'ALL';
  }

  private parseSource(value: string | null): TransactionEvidenceSource {
    return value === 'JOURNEY' || value === 'EXCLUSION_AUDIT' || value === 'RULE_HIT'
      ? value
      : 'ALL';
  }

  private parseStage(value: string | null): TransactionStage {
    return value === 'SELECTION' ||
      value === 'TRANSACTION_JOIN' ||
      value === 'TRANSFORMATION' ||
      value === 'EXCLUSION' ||
      value === 'RULE_HIT'
      ? value
      : 'ALL';
  }

  private parseOutcome(value: string | null): TransactionOutcome {
    return value === 'SUCCESS' || value === 'ERROR' || value === 'PENDING' || value === 'EXCLUDED'
      ? value
      : 'ALL';
  }
}
