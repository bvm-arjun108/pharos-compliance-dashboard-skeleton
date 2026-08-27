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
type TransactionOutcome = 'ALL' | 'SUCCESS' | 'ERROR' | 'PENDING' | 'EXCLUDED';
type TransactionStatus =
  | 'ALL'
  | 'SUCCESS'
  | 'FAILED'
  | 'ERROR'
  | 'EXCLUDED'
  | 'NOT_YET_REPORTED'
  | 'REPORTED'
  | 'NOT_REPORTED';
type TransactionEvidenceLevel =
  | 'RECORD_LEVEL'
  | 'PARTIAL_RECORD_LEVEL'
  | 'AGGREGATE_ONLY'
  | 'NO_RECORDS';
type TransactionSortDirection = 'ASC' | 'DESC';

type ReportMode = 'BATCH' | 'PERIOD';

interface BatchReportContext {
  kind: 'BATCH';
  reportGroupId: number;
  reportGroupName: string | null;
  batchId: string;
  sequenceNumber: number;
  countryCode: string;
  countryName: string;
  reportingPeriodFrom: string | null;
  reportingPeriodTo: string | null;
}

/** No single batch — a KPI (e.g. total excluded transactions) can span many batches, so this
 *  covers every batch matching the date range / report group / country filter instead. */
interface PeriodReportContext {
  kind: 'PERIOD';
  reportGroupId: number | null;
  reportGroupName: string | null;
  countryCode: string;
  countryName: string;
  fromDate: string;
  toDate: string;
  batchCount: number;
}

type ReportContext = BatchReportContext | PeriodReportContext;

interface TransactionEvidenceRecord {
  recordKey: string;
  identifier: string;
  mtcn: string | null;
  batchId: string | null;
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

interface EvidenceDetail {
  primary: string;
  extras: string[];
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

/** Normalized shape the template renders, after tagging whichever backend response arrived
 *  (batch-scoped or period-wide) with its context's `kind`. */
interface TransactionReportResponse {
  context: ReportContext;
  metric: TransactionMetric | null;
  metricLabel: string;
  aggregateCount: number;
  availableRecordCount: number;
  matchingRecordCount: number;
  evidenceLevel: TransactionEvidenceLevel;
  evidenceMessage: string;
  outcomeBreakdown?: TransactionOutcomeBreakdown;
  stageBreakdown?: TransactionStageBreakdown[];
  transactions: TransactionEvidenceRecord[];
  search: string;
  source?: TransactionEvidenceSource;
  outcome: TransactionOutcome;
  status: TransactionStatus;
  sortDirection?: TransactionSortDirection;
  page: number;
  size: number;
}

/** Raw shape of GET /api/v1/transactions/report, before the context is tagged 'BATCH'. */
interface RawBatchReportResponse {
  context: Omit<BatchReportContext, 'kind'>;
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
  outcome: TransactionOutcome;
  status: TransactionStatus;
  sortDirection?: TransactionSortDirection;
  page: number;
  size: number;
}

/** Raw shape of GET /api/v1/transactions/period-report, before the context is tagged 'PERIOD'. */
interface RawPeriodReportResponse {
  context: Omit<PeriodReportContext, 'kind'>;
  metricLabel: string;
  aggregateCount: number;
  availableRecordCount: number;
  matchingRecordCount: number;
  evidenceLevel: TransactionEvidenceLevel;
  evidenceMessage: string;
  transactions: TransactionEvidenceRecord[];
  search: string;
  outcome: TransactionOutcome;
  status: TransactionStatus;
  sortDirection?: TransactionSortDirection;
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
  readonly hasContext = signal(false);
  readonly mode = signal<ReportMode | null>(null);

  readonly reportGroupId = signal<number | null>(null);
  readonly batchId = signal('');
  readonly sequenceNumber = signal<number | null>(null);
  // Period mode only — the date range / country a KPI's total was computed over, when there is
  // no single batch to scope the report to.
  readonly fromDate = signal('');
  readonly toDate = signal('');
  readonly country = signal('ALL');
  readonly metric = signal<TransactionMetric>('ALL');
  readonly search = signal('');
  readonly source = signal<TransactionEvidenceSource>('ALL');
  readonly status = signal<TransactionStatus>('ALL');
  readonly sortDirection = signal<TransactionSortDirection>('DESC');
  readonly page = signal(0);
  readonly size = signal(25);
  readonly expandedRecordKey = signal<string | null>(null);

  ngOnInit(): void {
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(params => {
      this.readRouteState(params);
      if (this.hasContext()) {
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

  setStatus(event: Event): void {
    this.status.set((event.target as HTMLSelectElement).value as TransactionStatus);
  }

  toggleSortDirection(): void {
    this.updateRoute({
      sortDirection: this.sortDirection() === 'DESC' ? 'ASC' : 'DESC',
      page: 0
    });
  }

  applyFilters(event: SubmitEvent): void {
    event.preventDefault();
    this.updateRoute({
      search: this.search().trim() || null,
      status: this.status(),
      page: 0
    });
  }

  clearFilters(): void {
    this.updateRoute({ search: null, status: 'ALL', page: 0 });
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

  /** Same deep link as openBatchView(), but for an arbitrary batch id referenced from a row (its
   *  own batchId, or a "previously reported in" batch) — not necessarily the batch under
   *  investigation. Omits sequenceNumber since evidence rows don't carry the target batch's
   *  reconciliation sequence; the explorer falls back to the first (only) match for that batch id.
   *  Also omits reportGroupId in period mode: the page's own reportGroupId filter (often null, for
   *  "all report groups") isn't necessarily the group that batch actually belongs to when the
   *  period spans more than one — batchId text-matches uniquely on its own. */
  viewBatch(batchId: string | null, event: Event): void {
    event.stopPropagation();
    if (!batchId) {
      return;
    }
    const queryParams: Record<string, string | number | null> = {
      batchId,
      fromDate: '2000-01-01',
      toDate: '2099-12-31'
    };
    if (this.mode() === 'BATCH') {
      queryParams['reportGroupId'] = this.reportGroupId();
    }
    void this.router.navigate(['/batches/explorer'], { queryParams });
  }

  viewReportConfig(reportGroupId: number | null, event: Event): void {
    event.stopPropagation();
    if (reportGroupId === null) {
      return;
    }
    void this.router.navigate(['/report-config'], {
      queryParams: { reportGroupId, status: 'ALL' }
    });
  }

  outcomeSummary(report: TransactionReportResponse): string {
    const breakdown = report.outcomeBreakdown;
    if (!breakdown) {
      return '';
    }
    return `${breakdown.successCount} success, ${breakdown.errorCount} error, ${breakdown.pendingCount} pending, ${breakdown.excludedCount} excluded, out of ${breakdown.totalCount} total`;
  }

  /** "PORTUGAL OBJECTIVE" / "Report group 123" when scoped to one group, "All report groups"
   *  when a period-mode report spans every group matching its date range/country filter. */
  contextGroupLabel(context: ReportContext): string {
    if (context.reportGroupId === null) {
      return 'All report groups';
    }
    return context.reportGroupName || `Report group ${context.reportGroupId}`;
  }

  recordDetail(record: TransactionEvidenceRecord): EvidenceDetail {
    if (record.source === 'RULE_HIT') {
      return { primary: record.status === 'REPORTED' ? 'Reported' : 'Not yet reported', extras: [] };
    }
    const raw = record.exclusionReason ?? record.skipReason ?? record.comments;
    if (!raw) {
      return {
        primary: record.processingComplete === false ? 'Processing incomplete' : 'No additional detail',
        extras: []
      };
    }
    const issues = this.parseIssueList(raw);
    if (issues) {
      const [first, ...rest] = issues;
      const extras: string[] = [];
      if (first.field) { extras.push(`Field: ${first.field}`); }
      if (first.ruleSet) { extras.push(`Rule set: ${this.humanize(first.ruleSet)}`); }
      if (first.errorCode) { extras.push(`Error code: ${first.errorCode}`); }
      if (rest.length > 0) { extras.push(`+${rest.length} more issue${rest.length > 1 ? 's' : ''}`); }
      return { primary: first.message, extras };
    }
    return { primary: this.humanizeIfCode(raw), extras: [] };
  }

  /** Converts a SCREAMING_SNAKE_CASE / mixed(PAREN) code into "Screaming Snake Case (Paren)". */
  humanize(value: string | null | undefined): string {
    if (!value) {
      return 'Not available';
    }
    return value
      .replace(/_/g, ' ')
      .replace(/\(/g, ' (')
      .trim()
      .split(/\s+/)
      .map(word => {
        const match = word.match(/^(\()?(.*?)(\))?$/);
        if (!match || !match[2]) {
          return word;
        }
        const [, open, core, close] = match;
        const lower = core.toLowerCase();
        return `${open ?? ''}${lower.charAt(0).toUpperCase()}${lower.slice(1)}${close ?? ''}`;
      })
      .join(' ');
  }

  ruleIdsDisplay(record: TransactionEvidenceRecord): string {
    if (record.ruleId) {
      return record.ruleId;
    }
    const ids = [...new Set(this.ruleHits(record).map(hit => hit.ruleId).filter((id): id is string => !!id))];
    return ids.length > 0 ? ids.join(', ') : 'Not available';
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

  /** A "code" is a machine constant (SCREAMING_SNAKE_CASE, dotted IDs, etc) — humanize only those,
   *  leaving free-text config values (e.g. an exclusion reason sentence) untouched. */
  private humanizeIfCode(value: string): string {
    return /^[A-Z0-9_()./-]+$/.test(value) ? this.humanize(value) : value;
  }

  /** Parses a skip/exclusion reason that is a JSON array of exception objects (or plain strings)
   *  into a simple {message, field, ruleSet, errorCode} shape the template can render legibly.
   *  Returns null when the raw value isn't such a JSON array, so callers fall back to plain text. */
  private parseIssueList(
    raw: string
  ): { message: string; field: string | null; ruleSet: string | null; errorCode: string | null }[] | null {
    let parsed: unknown;
    try {
      parsed = JSON.parse(raw);
    } catch {
      return null;
    }
    if (!Array.isArray(parsed) || parsed.length === 0) {
      return null;
    }
    return parsed.map(entry => {
      if (entry && typeof entry === 'object') {
        const obj = entry as Record<string, unknown>;
        const message = (obj['Exception'] ?? obj['exception'] ?? obj['Message'] ?? obj['message']) as
          | string
          | undefined;
        return {
          message: message ?? JSON.stringify(obj),
          field: ((obj['Path'] ?? obj['path']) as string | undefined) ?? null,
          ruleSet: ((obj['RuleSetName'] ?? obj['ruleSetName']) as string | undefined) ?? null,
          errorCode: ((obj['ErrorCode'] ?? obj['errorCode']) as string | undefined) ?? null
        };
      }
      return { message: String(entry), field: null, ruleSet: null, errorCode: null };
    });
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

    if (this.mode() === 'BATCH') {
      this.loadBatchReport();
    } else if (this.mode() === 'PERIOD') {
      this.loadPeriodReport();
    }
  }

  private loadBatchReport(): void {
    const params = new HttpParams()
      .set('reportGroupId', this.reportGroupId()!)
      .set('batchId', this.batchId())
      .set('sequenceNumber', this.sequenceNumber()!)
      .set('metric', this.metric())
      .set('search', this.search().trim())
      .set('source', this.source())
      .set('status', this.status())
      .set('sortDirection', this.sortDirection())
      .set('page', this.page())
      .set('size', this.size());

    this.http.get<RawBatchReportResponse>('/api/v1/transactions/report', { params }).subscribe({
      next: raw => {
        this.report.set({
          ...raw,
          context: { kind: 'BATCH', ...raw.context }
        });
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.error.set('The transaction evidence report could not be loaded.');
      }
    });
  }

  /** Period mode: no batchId, so there is no single reconciliation batch to fetch evidence for —
   *  scoped instead by date range and the optional report group/country filters, matching however
   *  many batches the underlying KPI actually summed. See openExcludedTransactionsExplorer() in
   *  home.component.ts for where this is linked from. */
  private loadPeriodReport(): void {
    let params = new HttpParams()
      .set('fromDate', this.fromDate())
      .set('toDate', this.toDate())
      .set('country', this.country())
      .set('search', this.search().trim())
      .set('status', this.status())
      .set('sortDirection', this.sortDirection())
      .set('page', this.page())
      .set('size', this.size());
    if (this.reportGroupId() !== null) {
      params = params.set('reportGroupId', this.reportGroupId()!);
    }

    this.http.get<RawPeriodReportResponse>('/api/v1/transactions/period-report', { params }).subscribe({
      next: raw => {
        this.report.set({
          ...raw,
          metric: null,
          context: { kind: 'PERIOD', ...raw.context }
        });
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
    const fromDate = params.get('fromDate')?.trim() ?? '';
    const toDate = params.get('toDate')?.trim() ?? '';

    this.reportGroupId.set(Number.isInteger(reportGroupId) && reportGroupId > 0 ? reportGroupId : null);
    this.sequenceNumber.set(Number.isInteger(sequenceNumber) && sequenceNumber > 0 ? sequenceNumber : null);
    this.batchId.set(batchId);
    this.fromDate.set(fromDate);
    this.toDate.set(toDate);
    this.country.set(params.get('country')?.trim() || 'ALL');
    this.metric.set(this.parseMetric(params.get('metric')));
    this.search.set(params.get('search') ?? '');
    this.source.set(this.parseSource(params.get('source')));
    this.status.set(this.parseStatus(params.get('status')));
    this.sortDirection.set(params.get('sortDirection') === 'ASC' ? 'ASC' : 'DESC');
    this.page.set(Math.max(0, Number(params.get('page') ?? 0) || 0));

    if (batchId && this.reportGroupId() !== null && this.sequenceNumber() !== null) {
      this.mode.set('BATCH');
    } else if (fromDate && toDate) {
      this.mode.set('PERIOD');
    } else {
      this.mode.set(null);
    }
    this.hasContext.set(this.mode() !== null);
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

  private parseStatus(value: string | null): TransactionStatus {
    return value === 'SUCCESS' ||
      value === 'FAILED' ||
      value === 'ERROR' ||
      value === 'EXCLUDED' ||
      value === 'NOT_YET_REPORTED' ||
      value === 'REPORTED' ||
      value === 'NOT_REPORTED'
      ? value
      : 'ALL';
  }
}
