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
  | 'FILTRATION_VARIANCE'
  | 'RECONCILIATION_VARIANCE'
  | 'TRANSFORMER_OUTPUT';
type TransactionEvidenceSource = 'ALL' | 'JOURNEY' | 'EXCLUSION_AUDIT';
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
}

interface TransactionReportResponse {
  context: TransactionReportContext;
  metric: TransactionMetric;
  metricLabel: string;
  aggregateCount: number;
  availableRecordCount: number;
  matchingRecordCount: number;
  evidenceLevel: TransactionEvidenceLevel;
  evidenceMessage: string;
  transactions: TransactionEvidenceRecord[];
  search: string;
  source: TransactionEvidenceSource;
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
  readonly outcome = signal<TransactionOutcome>('ALL');
  readonly page = signal(0);
  readonly size = signal(100);

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

  setSource(event: Event): void {
    this.source.set((event.target as HTMLSelectElement).value as TransactionEvidenceSource);
  }

  setOutcome(event: Event): void {
    this.outcome.set((event.target as HTMLSelectElement).value as TransactionOutcome);
  }

  applyFilters(event: SubmitEvent): void {
    event.preventDefault();
    this.updateRoute({
      search: this.search().trim() || null,
      source: this.source(),
      outcome: this.outcome(),
      page: 0
    });
  }

  clearFilters(): void {
    this.updateRoute({ search: null, source: 'ALL', outcome: 'ALL', page: 0 });
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

  coverage(report: TransactionReportResponse): string {
    if (report.aggregateCount === 0) {
      return report.availableRecordCount === 0 ? 'N/A' : '100%';
    }
    return `${Math.min(100, (report.availableRecordCount / report.aggregateCount) * 100).toFixed(1)}%`;
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
    return source === 'JOURNEY' ? 'Latest journey' : 'Exclusion audit';
  }

  recordDetail(record: TransactionEvidenceRecord): string {
    return (
      record.exclusionReason ??
      record.skipReason ??
      record.comments ??
      (record.processingComplete === false ? 'Processing incomplete' : 'No additional detail')
    );
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
      'FILTRATION_VARIANCE', 'RECONCILIATION_VARIANCE', 'TRANSFORMER_OUTPUT'
    ];
    return metrics.includes(value as TransactionMetric) ? (value as TransactionMetric) : 'ALL';
  }

  private parseSource(value: string | null): TransactionEvidenceSource {
    return value === 'JOURNEY' || value === 'EXCLUSION_AUDIT' ? value : 'ALL';
  }

  private parseOutcome(value: string | null): TransactionOutcome {
    return value === 'SUCCESS' || value === 'ERROR' || value === 'PENDING' || value === 'EXCLUDED'
      ? value
      : 'ALL';
  }
}
